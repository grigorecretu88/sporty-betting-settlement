package com.sporty.settlement.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.kafka")
@Validated
public class KafkaTopicsProperties {

    @Valid
    private final Topics topics = new Topics();

    public Topics getTopics() {
        return topics;
    }

    public static class Topics {
        /**
         * Topic for inbound EventOutcome messages.
         */
        @NotBlank
        private String eventOutcomes = "event-outcomes";

        public String getEventOutcomes() {
            return eventOutcomes;
        }

        public void setEventOutcomes(String eventOutcomes) {
            this.eventOutcomes = eventOutcomes;
        }
    }
}
