package com.sporty.settlement.messaging;

/**
 * Outbound port for publishing EventOutcome messages.
 *
 * Keeps the API/application layer decoupled from KafkaTemplate.
 */
public interface EventOutcomeProducer {

    void send(String key, String payload, String correlationId);
}
