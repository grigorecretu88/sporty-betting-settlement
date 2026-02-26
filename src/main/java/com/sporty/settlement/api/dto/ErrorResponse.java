package com.sporty.settlement.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Stable error contract for API callers.
 *
 * Keep it small and predictable: code + message + correlationId.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        String correlationId,
        Instant timestamp
) {
}
