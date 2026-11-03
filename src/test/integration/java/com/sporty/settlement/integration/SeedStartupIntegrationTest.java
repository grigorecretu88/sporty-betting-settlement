package com.sporty.settlement.integration;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"app.outbox.dispatcher.enabled=false", "spring.kafka.listener.auto-startup=false"})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeedStartupIntegrationTest {
    @Autowired StartupProbe probe;

    @Test
    void seededBetsExistBeforeMessagingLifecycleCanStart() {
        assertThat(probe.observedAtLifecycleStart).containsExactlyInAnyOrderEntriesOf(Map.of(
                "bet-1", BetStatus.PENDING, "bet-2", BetStatus.PENDING, "bet-3", BetStatus.PENDING));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        StartupProbe startupProbe(BetRepository bets) {
            return new StartupProbe(bets);
        }
    }

    static class StartupProbe implements SmartLifecycle {
        private final BetRepository bets;
        private Map<String, BetStatus> observedAtLifecycleStart;
        private boolean running;

        StartupProbe(BetRepository bets) {
            this.bets = bets;
        }

        @Override
        public void start() {
            observedAtLifecycleStart = bets.findAll().stream()
                    .collect(Collectors.toMap(Bet::getBetId, Bet::getStatus));
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            // Observe even before the Kafka listener's lifecycle phase.
            return Integer.MIN_VALUE;
        }
    }
}
