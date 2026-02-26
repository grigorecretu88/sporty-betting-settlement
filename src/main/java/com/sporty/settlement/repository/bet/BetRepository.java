package com.sporty.settlement.repository.bet;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.domain.BetView;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BetRepository extends JpaRepository<Bet, String> {
    List<Bet> findByEventId(String eventId);

    List<Bet> findByEventIdAndStatus(String eventId, BetStatus status);

    List<BetView> findAllBy();

}
