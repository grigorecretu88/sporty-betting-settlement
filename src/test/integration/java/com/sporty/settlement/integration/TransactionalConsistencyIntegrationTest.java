package com.sporty.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeEntity;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeRepository;
import com.sporty.settlement.service.OutcomeProcessingResult;
import com.sporty.settlement.service.SettlementMatchingService;
import com.sporty.settlement.service.outbox.OutboxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

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
    @Autowired BetRepository bets;
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
            Optional<ProcessedEventOutcomeEntity> result = Optional.ofNullable(entityManager.find(
                    ProcessedEventOutcomeEntity.class, "event-300"));
            assertThat(result).isEqualTo(Optional.empty());
            bothReadAbsent.await(5, TimeUnit.SECONDS);
            return result;
        }).when(outcomes).findById("event-300");
        Callable<OutcomeProcessingResult> task = () -> {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("start timeout");
            }
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
                    if (status == OutcomeProcessingResult.Status.ACCEPTED) {
                        accepted++;
                    } else {
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
    void outcomeWithoutBetsRecordsIdentityWithoutIntents() {
        assertThat(matching.process(new EventOutcomeV1("empty-event", "Empty", "a"), "corr"))
                .isEqualTo(OutcomeProcessingResult.accepted(0));
        assertThat(outcomes.findById("empty-event")).isPresent();
        assertThat(outbox.count()).isZero();
    }

    private static String deterministicId(String eventId, String winnerId, String betId) {
        return UUID.nameUUIDFromBytes((frame(eventId) + frame(winnerId) + frame(betId))
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
    }
}
