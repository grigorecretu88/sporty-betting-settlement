package com.sporty.settlement.integration;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.messaging.rocket.InMemoryBetSettlementBroker;
import com.sporty.settlement.messaging.rocket.MockRocketMqPublisher;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"app.outbox.dispatcher.enabled=false", "spring.kafka.listener.auto-startup=false"})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MockSettlementIntegrationTest {
    @Autowired MockRocketMqPublisher publisher;
    @Autowired InMemoryBetSettlementBroker broker;
    @Autowired BetRepository bets;

    @Test
    void transportDuplicatesAndPoisonCommandPreserveActualPersistedSettlement() {
        var winner = command("winner", "bet-1", "user-1", "team-a", "10.00");
        publisher.publish(winner);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(bets.findById("bet-1").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_WON));
        Instant settledAt = bets.findById("bet-1").orElseThrow().getSettledAt();
        publisher.publish(winner);
        publisher.publish(command("invalid-user", "bet-2", "wrong-user", "team-b", "25.00"));
        publisher.publish(command("loser", "bet-2", "user-2", "team-b", "25.00"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(broker.deadLetterCount()).isEqualTo(1);
            assertThat(bets.findById("bet-2").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_LOST);
        });
        var persistedWinner = bets.findById("bet-1").orElseThrow();
        assertThat(persistedWinner.getSettledAt()).isEqualTo(settledAt);
        assertThat(persistedWinner.getOutcomeWinnerId()).isEqualTo("team-a");
        assertThat(bets.findById("bet-2").orElseThrow().getOutcomeWinnerId()).isEqualTo("team-a");
        assertThat(bets.findById("bet-3").orElseThrow().getStatus()).isEqualTo(BetStatus.PENDING);
    }

    private static BetSettlementCommandV1 command(String id, String bet, String user, String selection, String amount) {
        return new BetSettlementCommandV1(id, bet, user, "event-100", "market-1", selection,
                new BigDecimal(amount), "team-a", Instant.now(), "mock-integration");
    }
}
