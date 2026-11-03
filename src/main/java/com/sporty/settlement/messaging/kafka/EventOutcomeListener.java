package com.sporty.settlement.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.logging.CorrelationIdContext;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.service.OutcomeProcessingResult;
import com.sporty.settlement.service.SettlementMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class EventOutcomeListener {
    private static final String KAFKA_KEY_MDC = "kafkaKey";

    private final ObjectMapper mapper;
    private final SettlementMatchingService matchingService;

    @KafkaListener(topics = "${app.kafka.topics.event-outcomes:event-outcomes}",
            groupId = "${app.kafka.consumer.group:settlement-group}")
    public void listen(ConsumerRecord<String, String> record) {
        String payload = record.value();
        String correlationId = extractCorrelationId(record)
                .orElseGet(() -> "kafka-" + UUID.randomUUID());

        CorrelationIdContext.run(correlationId, () -> processMessage(record, payload, correlationId));
    }

    private void processMessage(ConsumerRecord<String, String> record, String payload, String correlationId) {
        try {
            EventOutcomeV1 outcome = mapper.readValue(payload, EventOutcomeV1.class);
            String eventId = outcome.eventId();

            String previousKafkaKey = MDC.get(KAFKA_KEY_MDC);
            try (MDC.MDCCloseable ignoredKafkaKey = MDC.putCloseable(KAFKA_KEY_MDC, eventId)) {
                var result = matchingService.process(outcome, correlationId);
                if (result.status() == OutcomeProcessingResult.Status.ACCEPTED) {
                    log.info("Consumed event outcome eventId={} matchedBets={} topic={} partition={} offset={}",
                            eventId, result.settlementCount(), record.topic(), record.partition(), record.offset());
                } else if (result.status() == OutcomeProcessingResult.Status.DUPLICATE) {
                    log.info("Duplicate event outcome suppressed eventId={} winnerId={}",
                            eventId, outcome.eventWinnerId());
                }
            } finally {
                if (previousKafkaKey != null) {
                    MDC.put(KAFKA_KEY_MDC, previousKafkaKey);
                }
            }
        } catch (Exception failure) {
            // Retain correlation context here; the container logs the stack and retry attempt.
            log.warn("Outcome processing failed topic={} partition={} offset={} payloadLength={}",
                    record.topic(), record.partition(), record.offset(), payload == null ? 0 : payload.length());
            if (failure instanceof BetSettlementException settlementFailure) {
                throw settlementFailure;
            }
            throw new BetSettlementException("Failed to process event-outcome message", failure);
        }
    }

    private static Optional<String> extractCorrelationId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(CorrelationIdFilter.HEADER);
        if (header == null || header.value() == null) {
            return Optional.empty();
        }
        String value = new String(header.value(), StandardCharsets.UTF_8).trim();
        return CorrelationIdFilter.isValid(value) ? Optional.of(value) : Optional.empty();
    }
}
