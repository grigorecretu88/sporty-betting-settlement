package com.sporty.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.*;
import com.sporty.settlement.repository.outbox.*;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"app.outbox.dispatcher.enabled=false", "spring.kafka.listener.auto-startup=false"})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OutboxRecoveryIntegrationTest {
    @Autowired OutboxClaimService claims;
    @Autowired OutboxMessageRepository rows;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BetSettlementService settlements;
    @Autowired BetRepository bets;

    @Test
    void expiredClaimsExhaustBudgetAndLaterWorkStillProgresses() throws Exception {
        enqueue("exhausted");
        ClaimedOutboxMessage previous = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            var current = claims.claimNext().orElseThrow();
            assertThat(current.attempt()).isEqualTo(attempt);
            if (previous != null) {
                var stale = previous;
                assertThatThrownBy(() -> claims.markSent(stale)).isInstanceOf(IllegalStateException.class);
            }
            expire(current.id());
            previous = current;
        }
        enqueue("later");
        assertThat(claims.claimNext()).isEmpty();
        var failed = rows.findById("exhausted").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(10);
        assertThat(failed.getLastError()).contains("lease expiry", "unknown");
        var later = claims.claimNext().orElseThrow();
        assertThat(later.id()).isEqualTo("later");
        claims.markSent(later);
        assertThat(rows.count()).isEqualTo(2);
    }

    @Test
    void tenBrokerFailuresRetainTerminalDiagnostics() throws Exception {
        enqueue("failure");
        for (int attempt = 1; attempt <= 10; attempt++) {
            var claim = claims.claimNext().orElseThrow();
            claims.markFailed(claim, "broker unavailable", false);
            jdbc.update("update outbox_messages set next_attempt_at = ? where id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), "failure");
        }
        var failed = rows.findById("failure").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(10);
        assertThat(failed.getLastError()).isEqualTo("broker unavailable");
        assertThat(claims.claimNext()).isEmpty();
    }

    @Test
    void successfulSendWithExpiredLeaseRepeatsOnlyTheTransportEffect() throws Exception {
        enqueue("ambiguous");
        var sends = new AtomicInteger();
        var duplicates = new AtomicInteger();
        new OutboxDispatcher(claims, command -> {
            if (settlements.settle(command) == BetSettlementService.Result.DUPLICATE) duplicates.incrementAndGet();
            if (sends.incrementAndGet() == 1) expire("ambiguous");
        }, mapper).dispatch();
        assertThat(sends.get()).isEqualTo(2);
        assertThat(duplicates.get()).isEqualTo(1);
        assertThat(rows.findById("ambiguous").orElseThrow().getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(bets.findById("bet-1").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_WON);
    }

    @Test
    void meaningfulBatchDoesNotPreclaimOrLetDelayedFailureBlockLaterWork() throws Exception {
        for (int i = 0; i < 60; i++) enqueue("batch-" + i);
        var sends = new AtomicInteger();
        var dispatcher = new OutboxDispatcher(claims, command -> {
            assertThat(rows.countByStatus(OutboxStatus.PROCESSING)).isEqualTo(1);
            if (sends.incrementAndGet() == 20) throw new IllegalStateException("temporary outage");
        }, mapper);
        dispatcher.dispatch();
        assertThat(sends.get()).isBetween(1, 50);
        for (int i = 0; i < 60 && rows.countByStatus(OutboxStatus.SENT) < 59; i++) dispatcher.dispatch();
        assertThat(rows.countByStatus(OutboxStatus.SENT)).isGreaterThanOrEqualTo(59);
        jdbc.update("update outbox_messages set next_attempt_at = ? where status = 'PENDING'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        dispatcher.dispatch();
        assertThat(rows.countByStatus(OutboxStatus.SENT)).isEqualTo(60);
        assertThat(rows.count()).isEqualTo(60);
    }

    private void expire(String id) {
        jdbc.update("update outbox_messages set lease_until = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
    }

    private void enqueue(String id) throws Exception {
        var command = new BetSettlementCommandV1(id, "bet-1", "user-1", "event-100", "market-1",
                "team-a", new BigDecimal("10"), "team-a", Instant.now(), "recovery");
        rows.saveAndFlush(new OutboxMessageEntity(id, "bet-settlements", "BetSettlementCommandV1",
                mapper.writeValueAsString(command), "recovery", OutboxStatus.PENDING, Instant.now()));
    }
}
