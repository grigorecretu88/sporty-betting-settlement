package com.sporty.settlement.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record BetSettlementCommandV1(
        String commandId,
        String betId,
        String userId,
        String eventId,
        String eventMarketId,
        String eventWinnerId,
        BigDecimal betAmount,
        String outcomeWinnerId,
        Instant occurredAt,
        String correlationId
) {
    public BetSettlementCommandV1 {
        commandId = requireText(commandId, "commandId");
        betId = requireText(betId, "betId");
        userId = requireText(userId, "userId");
        eventId = requireText(eventId, "eventId");
        eventMarketId = requireText(eventMarketId, "eventMarketId");
        eventWinnerId = requireText(eventWinnerId, "eventWinnerId");
        outcomeWinnerId = requireText(outcomeWinnerId, "outcomeWinnerId");
        if (betAmount == null || betAmount.signum() <= 0) {
            throw new IllegalArgumentException("betAmount must be positive");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
