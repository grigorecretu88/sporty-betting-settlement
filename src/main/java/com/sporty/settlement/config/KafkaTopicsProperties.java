package com.sporty.settlement.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.kafka.topics")
@Validated
public class KafkaTopicsProperties {

    @NotBlank
    private String eventOutcomes = "event-outcomes";

    public String getEventOutcomes() {
        return eventOutcomes;
    }

    public void setEventOutcomes(String eventOutcomes) {
        this.eventOutcomes = eventOutcomes;
    }
}
