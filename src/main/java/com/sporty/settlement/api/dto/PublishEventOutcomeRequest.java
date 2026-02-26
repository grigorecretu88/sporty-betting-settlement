package com.sporty.settlement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublishEventOutcomeRequest(
        @NotBlank
        @Size(max = 64)
        String eventId,

        @NotBlank
        @Size(max = 255)
        String eventName,

        @NotBlank
        @Size(max = 64)
        String eventWinnerId
) {
}