package com.sporty.settlement.api.dto;

import com.sporty.settlement.domain.BetView;

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
    public static BetResponse from(BetView v) {
        return new BetResponse(
                v.getBetId(),
                v.getUserId(),
                v.getEventId(),
                v.getEventMarketId(),
                v.getEventWinnerId(),
                v.getBetAmount(),
                String.valueOf(v.getStatus()), // works for String or enum
                v.getSettledAt(),
                v.getOutcomeWinnerId()
        );
    }
}