package com.sporty.settlement.domain;

public record EventOutcomeV1(
        String eventId,
        String eventName,
        String eventWinnerId
) {
    public EventOutcomeV1 {
        eventId = requireText(eventId, "eventId");
        eventName = requireText(eventName, "eventName");
        eventWinnerId = requireText(eventWinnerId, "eventWinnerId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
