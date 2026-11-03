package com.sporty.settlement.service;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.repository.bet.BetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class BetSettlementService {
    public enum Result { SETTLED_WON, SETTLED_LOST, DUPLICATE }

    private final BetRepository betRepository;

    @Transactional
    public Result settle(BetSettlementCommandV1 command) {
        Bet bet = betRepository.findLockedByBetId(command.betId())
                .orElseThrow(() -> new BetSettlementException("Bet not found: " + command.betId()));

        if (!bet.getEventId().equals(command.eventId())) {
            throw new BetSettlementException("Event mismatch for bet " + command.betId());
        }
        if (!bet.getUserId().equals(command.userId())
                || !bet.getEventMarketId().equals(command.eventMarketId())
                || !bet.getEventWinnerId().equals(command.eventWinnerId())
                || bet.getBetAmount().compareTo(command.betAmount()) != 0) {
            throw new BetSettlementException("Settlement command does not match persisted bet " + command.betId());
        }

        if (bet.getStatus() != BetStatus.PENDING) {
            if (command.outcomeWinnerId().equals(bet.getOutcomeWinnerId())) {
                log.info("Duplicate settlement ignored commandId={} betId={} status={}",
                        command.commandId(), bet.getBetId(), bet.getStatus());
                return Result.DUPLICATE;
            }
            throw new BetSettlementException("Conflicting settlement for bet " + command.betId()
                    + ": existing winner=" + bet.getOutcomeWinnerId()
                    + ", received winner=" + command.outcomeWinnerId());
        }

        bet.markSettled(command.outcomeWinnerId(), Instant.now());
        log.info("Bet settled commandId={} betId={} eventId={} status={}",
                command.commandId(), bet.getBetId(), bet.getEventId(), bet.getStatus());
        return bet.getStatus() == BetStatus.SETTLED_WON ? Result.SETTLED_WON : Result.SETTLED_LOST;
    }
}
