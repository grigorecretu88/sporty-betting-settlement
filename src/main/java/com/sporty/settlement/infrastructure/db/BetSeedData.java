package com.sporty.settlement.infrastructure.db;

import com.sporty.settlement.domain.Bet;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class BetSeedData {

    @Bean
    CommandLineRunner seedBets(BetRepository repo) {
        return args -> {
            if (repo.count() > 0) return;

            repo.saveAll(List.of(
                    new Bet("bet-1", "user-1", "event-100", "market-1", "team-a", 10.0),
                    new Bet("bet-2", "user-2", "event-100", "market-1", "team-b", 25.0),
                    new Bet("bet-3", "user-3", "event-200", "market-9", "team-x", 5.0)
            ));
        };
    }
}
