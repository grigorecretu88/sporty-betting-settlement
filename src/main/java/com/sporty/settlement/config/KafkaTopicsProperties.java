package com.sporty.settlement.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.kafka.topics")
@Validated
public record KafkaTopicsProperties(
        @DefaultValue("event-outcomes") @NotBlank String eventOutcomes
) {
}
