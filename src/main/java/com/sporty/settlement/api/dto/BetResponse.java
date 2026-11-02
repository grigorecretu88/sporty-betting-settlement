package com.sporty.settlement.api.dto;

import com.sporty.settlement.domain.Bet;

import java.math.BigDecimal;
import java.time.Instant;

public record BetResponse(
        String betId,
        String userId,
        String eventId,
        String eventMarketId,
        String eventWinnerId,
        BigDecimal betAmount,
        String status,
        Instant settledAt,
        String outcomeWinnerId
) {
    public static BetResponse from(Bet bet) {
        return new BetResponse(
                bet.getBetId(),
                bet.getUserId(),
                bet.getEventId(),
                bet.getEventMarketId(),
                bet.getEventWinnerId(),
                bet.getBetAmount(),
                bet.getStatus().name(),
                bet.getSettledAt(),
                bet.getOutcomeWinnerId()
        );
    }
}
