package com.sporty.settlement.service;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeEntity;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeRepository;
import com.sporty.settlement.service.outbox.OutboxDispatcher;
import com.sporty.settlement.service.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class SettlementMatchingService {

    private final BetRepository betRepository;
    private final OutboxService outbox;
    private final ProcessedEventOutcomeRepository outcomeRepository;

    /**
     * Matches bets for an outcome and writes settlement commands to the outbox.
     * Publishing happens asynchronously via {@link OutboxDispatcher}.
     */
    @Transactional
    public OutcomeProcessingResult process(EventOutcomeV1 outcome, String correlationId) {
        ProcessedEventOutcomeEntity existing = outcomeRepository.findById(outcome.eventId()).orElse(null);
        if (existing != null) {
            if (existing.getWinnerId().equals(outcome.eventWinnerId())) {
                return OutcomeProcessingResult.duplicate();
            }
            log.error("Conflicting outcome rejected eventId={} acceptedWinnerId={} receivedWinnerId={}",
                    outcome.eventId(), existing.getWinnerId(), outcome.eventWinnerId());
            return OutcomeProcessingResult.conflict();
        }

        outcomeRepository.save(new ProcessedEventOutcomeEntity(
                outcome.eventId(), outcome.eventName(), outcome.eventWinnerId(), Instant.now()));

        List<Bet> bets = betRepository.findByEventIdAndStatus(outcome.eventId(), BetStatus.PENDING);
        log.info("Matching bets for eventId={} openBets={}", outcome.eventId(), bets.size());
        for (Bet bet : bets) {
            outbox.enqueue(settlementCommand(bet, outcome, correlationId));
        }
        // Force uniqueness/serialization failures to surface before this transaction returns.
        outcomeRepository.flush();
        return OutcomeProcessingResult.accepted(bets.size());
    }

    private static BetSettlementCommandV1 settlementCommand(
            Bet bet, EventOutcomeV1 outcome, String correlationId) {
        return new BetSettlementCommandV1(
                deterministicCommandId(outcome.eventId(), outcome.eventWinnerId(), bet.getBetId()),
                bet.getBetId(),
                bet.getUserId(),
                bet.getEventId(),
                bet.getEventMarketId(),
                bet.getEventWinnerId(),
                bet.getBetAmount(),
                outcome.eventWinnerId(),
                Instant.now(),
                correlationId
        );
    }

    static String deterministicCommandId(String eventId, String winnerId, String betId) {
        String identity = frame(eventId) + frame(winnerId) + frame(betId);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
    }
}
