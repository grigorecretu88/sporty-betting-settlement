package com.sporty.settlement.application;

import com.sporty.settlement.application.ports.BetSettlementPublisher;
import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.infrastructure.db.BetRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class SettlementMatchingService {

    private final BetRepository betRepository;
    private final BetSettlementPublisher publisher;

    public SettlementMatchingService(BetRepository betRepository, BetSettlementPublisher publisher) {
        this.betRepository = betRepository;
        this.publisher = publisher;
    }

    public int matchAndPublish(EventOutcomeV1 outcome) {
        List<Bet> bets = betRepository.findByEventId(outcome.eventId());
        for (Bet b : bets) {
            publisher.publish(new BetSettlementCommandV1(
                    b.getBetId(),
                    b.getUserId(),
                    b.getEventId(),
                    b.getEventMarketId(),
                    b.getEventWinnerId(),
                    b.getBetAmount(),
                    outcome.eventWinnerId(),
                    Instant.now()
            ));
        }
        return bets.size();
    }
}
