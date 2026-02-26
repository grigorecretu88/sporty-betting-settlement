package com.sporty.settlement.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record BetSettlementCommandV1(
        String betId,
        String userId,
        String eventId,
        String eventMarketId,
        String eventWinnerId,
        BigDecimal betAmount,
        String outcomeWinnerId,
        Instant occurredAt,
        String correlationId
) {}
