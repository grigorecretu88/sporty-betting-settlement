package com.sporty.settlement.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Stable error contract for API callers. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String correlationId,
        Instant timestamp
) {
}
