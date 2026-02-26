package com.sporty.settlement.service;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.repository.bet.BetRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class SettlementMatchingService {

    private final BetRepository betRepository;
    private final OutboxService outbox;

    public SettlementMatchingService(BetRepository betRepository, OutboxService outbox) {
        this.betRepository = betRepository;
        this.outbox = outbox;
    }

    /**
     * Matches bets for an outcome and writes settlement commands to the outbox.
     * Publishing happens asynchronously via {@link OutboxDispatcher}.
     */
    public int matchAndEnqueue(EventOutcomeV1 outcome, String correlationId) {
        List<Bet> bets = betRepository.findByEventIdAndStatus(outcome.eventId(), BetStatus.PENDING);
        log.info("Matching bets for eventId={} openBets={}", outcome.eventId(), bets.size());
        for (Bet bet : bets) {
            outbox.enqueue("bet-settlements", new BetSettlementCommandV1(
                    bet.getBetId(),
                    bet.getUserId(),
                    bet.getEventId(),
                    bet.getEventMarketId(),
                    bet.getEventWinnerId(),
                    bet.getBetAmount(),
                    outcome.eventWinnerId(),
                    Instant.now(),
                    correlationId
            ), correlationId);
        }
        return bets.size();
    }
}
