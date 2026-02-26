package com.sporty.settlement.repository.bet;

import com.sporty.settlement.domain.Bet;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

@Configuration
public class BetSeedData {

    @Bean
    CommandLineRunner seedBets(BetRepository repo) {
        return args -> {
            if (repo.count() > 0) return;

            repo.saveAll(List.of(
                    new Bet("bet-1", "user-1", "event-100", "market-1", "team-a", new BigDecimal("10.00")),
                    new Bet("bet-2", "user-2", "event-100", "market-1", "team-b", new BigDecimal("25.00")),
                    new Bet("bet-3", "user-3", "event-200", "market-9", "team-x", new BigDecimal("5.00"))
            ));
        };
    }
}
