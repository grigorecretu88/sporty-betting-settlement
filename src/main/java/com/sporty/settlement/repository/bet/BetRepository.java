package com.sporty.settlement.repository.bet;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.domain.BetView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

public interface BetRepository extends JpaRepository<Bet, String> {
    List<Bet> findByEventIdAndStatus(String eventId, BetStatus status);

    List<BetView> findAllBy();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bet b where b.betId = :betId")
    Optional<Bet> findByIdForUpdate(String betId);

}
