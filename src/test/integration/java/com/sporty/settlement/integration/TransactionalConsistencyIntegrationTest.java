package com.sporty.settlement.integration;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeRepository;
import com.sporty.settlement.service.OutcomeProcessingResult;
import com.sporty.settlement.service.ClaimedOutboxMessage;
import com.sporty.settlement.service.OutboxClaimService;
import com.sporty.settlement.service.OutboxDispatcher;
import com.sporty.settlement.service.SettlementMatchingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "app.outbox.dispatcher.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
class TransactionalConsistencyIntegrationTest {
    @Autowired SettlementMatchingService matching;
    @Autowired OutboxMessageRepository outbox;
    @Autowired ProcessedEventOutcomeRepository outcomes;
    @Autowired BetRepository bets;
    @Autowired OutboxClaimService claims;
    @Autowired ObjectMapper mapper;

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

    @Test
    void concurrentEquivalentOutcomesCreateAtMostOneSetOfSettlementIntents() throws Exception {
        bets.saveAndFlush(new Bet("bet-4", "user-4", "event-300", "market-3", "team-z",
                new BigDecimal("15.00")));
        long initialOutboxCount = outbox.count();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<OutcomeProcessingResult> task = () -> {
            start.await(5, TimeUnit.SECONDS);
            return matching.process(new EventOutcomeV1("event-300", "Final", "team-z"), "corr");
        };
        Future<OutcomeProcessingResult> first = executor.submit(task);
        Future<OutcomeProcessingResult> second = executor.submit(task);
        start.countDown();

        int accepted = 0;
        int concurrencyFailures = 0;
        for (Future<OutcomeProcessingResult> future : java.util.List.of(first, second)) {
            try {
                if (future.get(10, TimeUnit.SECONDS).status() == OutcomeProcessingResult.Status.ACCEPTED) accepted++;
            } catch (ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                concurrencyFailures++;
            }
        }
        executor.shutdownNow();

        assertThat(accepted).isEqualTo(1);
        assertThat(concurrencyFailures).isEqualTo(1);
        assertThat(outcomes.findById("event-300")).isPresent();
        assertThat(outbox.count()).isEqualTo(initialOutboxCount + 1);
        assertThat(matching.process(new EventOutcomeV1("event-300", "Final", "team-z"), "retry").status())
                .isEqualTo(OutcomeProcessingResult.Status.DUPLICATE);
    }

    @Test
    void publicationFailureLeavesClaimedMessagePendingForRetry() throws Exception {
        OutboxMessageEntity row = outboxRow("retry-row", Instant.now());
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

        ClaimedOutboxMessage recovered = claims.claimBatch().stream()
                .filter(message -> message.id().equals("expired-row"))
                .findFirst().orElseThrow();

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

        claims.markFailed(claim, "persistent broker failure", false);

        OutboxMessageEntity failed = outbox.findById("terminal-row").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo("persistent broker failure");
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
