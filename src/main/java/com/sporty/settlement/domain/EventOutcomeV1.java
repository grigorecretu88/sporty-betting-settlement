package com.sporty.settlement.domain;

public record EventOutcomeV1(
        String eventId,
        String eventName,
        String eventWinnerId
) {}
