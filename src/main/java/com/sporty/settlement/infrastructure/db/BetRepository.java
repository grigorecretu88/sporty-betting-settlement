package com.sporty.settlement.infrastructure.db;

import com.sporty.settlement.domain.Bet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BetRepository extends JpaRepository<Bet, String> {
    List<Bet> findByEventId(String eventId);
}
