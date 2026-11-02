package com.sporty.settlement.integration;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeRepository;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeEntity;
import com.sporty.settlement.service.OutcomeProcessingResult;
import com.sporty.settlement.service.ClaimedOutboxMessage;
import com.sporty.settlement.service.OutboxClaimService;
import com.sporty.settlement.service.OutboxDispatcher;
import com.sporty.settlement.service.OutboxService;
import com.sporty.settlement.service.SettlementMatchingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.sporty.settlement.service.ApiPublicationClaimService;
import com.sporty.settlement.service.BetSettlementService;
import com.sporty.settlement.repository.idempotency.ApiPublicationRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "app.outbox.dispatcher.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TransactionalConsistencyIntegrationTest {
    @Autowired SettlementMatchingService matching;
    @Autowired OutboxService enqueue;
    @Autowired OutboxMessageRepository outbox;
    @SpyBean ProcessedEventOutcomeRepository outcomes;
    @Autowired ApiPublicationClaimService apiClaims;
    @Autowired ApiPublicationRepository publications;
    @Autowired BetSettlementService settlements;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired BetRepository bets;
    @Autowired OutboxClaimService claims;
    @Autowired ObjectMapper mapper;
    @PersistenceContext EntityManager entityManager;

    @Test
    void outboxRequiresCallerTransactionBeforePersistingIntent() {
        var command = new BetSettlementCommandV1("standalone", "bet-1", "user-1",
                "event-100", "market-1", "team-a", BigDecimal.TEN, "team-a", Instant.now(), null);
        assertThatThrownBy(() -> enqueue.enqueue(command))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void outboxInsertFailureRollsBackProcessedOutcomeIdentity() {
        String commandId = deterministicId("event-200", "team-x", "bet-3");
        outbox.saveAndFlush(new OutboxMessageEntity(commandId, "bet-settlements",
                BetSettlementCommandV1.class.getSimpleName(), "{}", "preexisting",
                OutboxStatus.PENDING, Instant.now()));

        assertThatThrownBy(() -> matching.process(
                new EventOutcomeV1("event-200", "Final", "team-x"), "corr"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outcomes.findById("event-200")).isEmpty();
        assertThat(outbox.count()).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentEquivalentOutcomesCreateAtMostOneSetOfSettlementIntents() throws Exception {
        bets.saveAndFlush(new Bet("bet-4", "user-4", "event-300", "market-3", "team-z",
                new BigDecimal("15.00")));
        long initialOutboxCount = outbox.count();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier bothReadAbsent = new CyclicBarrier(2);
        doAnswer(invocation -> {
            // EntityManager is Spring's transaction-bound proxy: each worker uses its own context.
            // Force both real reads before either transaction can insert the unique event ID.
            Object result = Optional.ofNullable(entityManager.find(
                    ProcessedEventOutcomeEntity.class, "event-300"));
            assertThat(result).isEqualTo(Optional.empty());
            bothReadAbsent.await(5, TimeUnit.SECONDS);
            return result;
        }).when(outcomes).findById("event-300");
        Callable<OutcomeProcessingResult> task = () -> {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            return matching.process(new EventOutcomeV1("event-300", "Final", "team-z"), "corr");
        };
        Future<OutcomeProcessingResult> first = executor.submit(task);
        Future<OutcomeProcessingResult> second = executor.submit(task);
        int accepted = 0;
        int other = 0;
        try {
            start.countDown();
            for (Future<OutcomeProcessingResult> future : List.of(first, second)) {
                try {
                    var status = future.get(10, TimeUnit.SECONDS).status();
                    if (status == OutcomeProcessingResult.Status.ACCEPTED) accepted++;
                    else {
                        assertThat(status).isEqualTo(OutcomeProcessingResult.Status.DUPLICATE);
                        other++;
                    }
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                    other++;
                }
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            reset(outcomes);
        }

        assertThat(accepted).isEqualTo(1);
        assertThat(other).isEqualTo(1);
        assertThat(outcomes.count()).isEqualTo(1);
        assertThat(outcomes.findById("event-300").orElseThrow().getWinnerId()).isEqualTo("team-z");
        var intent = outbox.findById(deterministicId("event-300", "team-z", "bet-4")).orElseThrow();
        var command = mapper.readValue(intent.getPayloadJson(), BetSettlementCommandV1.class);
        assertThat(command.betId()).isEqualTo("bet-4");
        assertThat(command.eventId()).isEqualTo("event-300");
        assertThat(command.outcomeWinnerId()).isEqualTo("team-z");
        assertThat(outbox.count()).isEqualTo(initialOutboxCount + 1);
        assertThat(matching.process(new EventOutcomeV1("event-300", "Final", "team-z"), "retry").status())
                .isEqualTo(OutcomeProcessingResult.Status.DUPLICATE);
    }

    @Test
    void publicationFailureLeavesClaimedMessagePendingForRetry() throws Exception {
        OutboxMessageEntity row = outboxRow("retry-row", Instant.now().minusSeconds(120));
        outbox.saveAndFlush(row);

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims,
                command -> { throw new RuntimeException("broker unavailable"); }, mapper);
        dispatcher.dispatch();

        OutboxMessageEntity retained = outbox.findById("retry-row").orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retained.getAttempts()).isEqualTo(1);
        assertThat(retained.getLastError()).contains("broker unavailable");
        assertThat(retained.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void expiredClaimIsRecoveredWithNewOwnershipToken() throws Exception {
        OutboxMessageEntity row = outboxRow("expired-row", Instant.now().minusSeconds(60));
        row.claim("abandoned-token", Instant.now().minusSeconds(1));
        outbox.saveAndFlush(row);

        ClaimedOutboxMessage recovered = claims.claimNext().orElseThrow();
        assertThat(recovered.id()).isEqualTo("expired-row");

        assertThat(recovered.claimToken()).isNotEqualTo("abandoned-token");
        assertThat(outbox.findById("expired-row").orElseThrow().getAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> claims.markSent(new ClaimedOutboxMessage(
                recovered.id(), "wrong-token", recovered.destination(), recovered.messageType(),
                recovered.payloadJson(), recovered.correlationId(), recovered.attempt())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(outbox.findById("expired-row").orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PROCESSING);
    }

    @Test
    void tenthFailedClaimRemainsVisibleAsTerminalFailure() throws Exception {
        OutboxMessageEntity row = outboxRow("terminal-row", Instant.now());
        for (int attempt = 1; attempt <= 10; attempt++) {
            row.claim("token-" + attempt, Instant.now().plusSeconds(30));
        }
        outbox.saveAndFlush(row);
        ClaimedOutboxMessage claim = new ClaimedOutboxMessage(
                row.getId(), row.getClaimToken(), row.getDestination(), row.getMessageType(),
                row.getPayloadJson(), row.getCorrelationId(), row.getAttempts());

        claims.markFailed(claim, "persistent broker failure" + "x".repeat(3000), false);

        OutboxMessageEntity failed = outbox.findById("terminal-row").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getLastError()).startsWith("persistent broker failure").hasSize(2000);
    }

    @Test
    void expiredApiClaimCannotCompleteOrReleaseNewOwner() {
        var original = apiClaims.claim("expiry-key", "hash");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publications.findLocked("expiry-key").orElseThrow()
                        .reclaim(original.token(), Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> apiClaims.complete(original)).isInstanceOf(IllegalStateException.class);
        var replacement = apiClaims.claim("expiry-key", "hash");
        assertThat(replacement.token()).isNotEqualTo(original.token());
        assertThatThrownBy(() -> apiClaims.complete(original)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> apiClaims.release(original)).isInstanceOf(IllegalStateException.class);
        apiClaims.complete(replacement);
        assertThat(apiClaims.claim("expiry-key", "hash").status())
                .isEqualTo(ApiPublicationClaimService.Status.ALREADY_PUBLISHED);
    }

    @RepeatedTest(5)
    void concurrentSettlementHasOneTransitionAndOneDuplicate() throws Exception {
        BetSettlementCommandV1 command = new BetSettlementCommandV1("concurrent", "bet-1", "user-1",
                "event-100", "market-1", "team-a", new BigDecimal("10.00"), "team-a", Instant.now(), "corr");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<BetSettlementService.Result> work = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            return settlements.settle(command);
        };
        try {
            var first = executor.submit(work);
            var second = executor.submit(work);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(BetSettlementService.Result.SETTLED_WON, BetSettlementService.Result.DUPLICATE);
            var settled = bets.findById("bet-1").orElseThrow();
            assertThat(settled.getOutcomeWinnerId()).isEqualTo("team-a");
            assertThat(settled.getStatus()).isEqualTo(BetStatus.SETTLED_WON);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void successfulPublicationAfterLeaseExpiryIsRecoveredWithoutLosingIntent() throws Exception {
        outbox.saveAndFlush(outboxRow("expiry-send", Instant.now().minusSeconds(60)));
        var sends = new AtomicInteger();
        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, command -> {
            if (sends.incrementAndGet() == 1) {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    var row = outbox.findById("expiry-send").orElseThrow();
                    row.claim(row.getClaimToken(), Instant.now().minusSeconds(1));
                });
            }
        }, mapper);
        dispatcher.dispatch();
        assertThat(sends.get()).isEqualTo(2);
        assertThat(outbox.findById("expiry-send").orElseThrow().getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void outcomeWithoutBetsRecordsIdentityWithoutIntents() {
        assertThat(matching.process(new EventOutcomeV1("empty-event", "Empty", "a"), "corr"))
                .isEqualTo(OutcomeProcessingResult.accepted(0));
        assertThat(outcomes.findById("empty-event")).isPresent();
        assertThat(outbox.count()).isZero();
    }
    private OutboxMessageEntity outboxRow(String id, Instant createdAt) throws Exception {
        BetSettlementCommandV1 command = new BetSettlementCommandV1(
                id, "bet-1", "user-1", "event-100", "market-1", "team-a",
                new BigDecimal("10.00"), "team-a", createdAt, "corr");
        return new OutboxMessageEntity(id, "bet-settlements",
                BetSettlementCommandV1.class.getSimpleName(), mapper.writeValueAsString(command),
                "corr", OutboxStatus.PENDING, createdAt);
    }

    private static String deterministicId(String eventId, String winnerId, String betId) {
        return UUID.nameUUIDFromBytes((frame(eventId) + frame(winnerId) + frame(betId))
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String frame(String value) { return value.length() + ":" + value; }
}
