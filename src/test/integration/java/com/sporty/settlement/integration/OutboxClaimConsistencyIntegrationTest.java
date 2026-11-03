package com.sporty.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.service.ClaimedOutboxMessage;
import com.sporty.settlement.service.OutboxClaimService;
import com.sporty.settlement.service.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.outbox.dispatcher.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OutboxClaimConsistencyIntegrationTest {
    @Autowired OutboxMessageRepository outbox;
    @Autowired OutboxClaimService claims;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void publicationFailureLeavesClaimedMessagePendingForRetry() throws Exception {
        outbox.saveAndFlush(outboxRow("retry-row", Instant.now().minusSeconds(120)));
        OutboxDispatcher dispatcher = new OutboxDispatcher(claims,
                command -> {
                    throw new RuntimeException("broker unavailable");
                }, mapper);

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

    private OutboxMessageEntity outboxRow(String id, Instant createdAt) throws Exception {
        BetSettlementCommandV1 command = new BetSettlementCommandV1(
                id, "bet-1", "user-1", "event-100", "market-1", "team-a",
                new BigDecimal("10.00"), "team-a", createdAt, "corr");
        return new OutboxMessageEntity(id, "bet-settlements",
                BetSettlementCommandV1.class.getSimpleName(), mapper.writeValueAsString(command),
                "corr", OutboxStatus.PENDING, createdAt);
    }
}
