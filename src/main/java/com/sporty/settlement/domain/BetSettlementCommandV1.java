package com.sporty.settlement.domain;

import java.time.Instant;

public record BetSettlementCommandV1(
        String betId,
        String userId,
        String eventId,
        String eventMarketId,
        String eventWinnerId,
        double betAmount,
        String outcomeWinnerId,
        Instant occurredAt
) {}
