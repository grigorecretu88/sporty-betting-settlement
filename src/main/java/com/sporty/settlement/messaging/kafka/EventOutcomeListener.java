package com.sporty.settlement.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.service.SettlementMatchingService;
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
public class EventOutcomeListener {

    private final ObjectMapper mapper;
    private final SettlementMatchingService matchingService;

    public EventOutcomeListener(ObjectMapper mapper,
                                SettlementMatchingService matchingService) {
        this.mapper = mapper;
        this.matchingService = matchingService;
    }

    @KafkaListener(topics = "${app.kafka.topics.event-outcomes:event-outcomes}",
            groupId = "${app.kafka.consumer.group:settlement-group}")
    public void listen(ConsumerRecord<String, String> record) {
        String message = record.value();
        String correlationId = extractCorrelationId(record)
                .orElseGet(() -> "kafka-" + UUID.randomUUID());

        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            EventOutcomeV1 outcome = mapper.readValue(message, EventOutcomeV1.class);

            String eventId = outcome.eventId();

            try (MDC.MDCCloseable ignoredKafkaKey = MDC.putCloseable("kafkaKey", eventId)) {
                var result = matchingService.process(outcome, correlationId);
                switch (result.status()) {
                    case ACCEPTED -> log.info("Consumed event outcome eventId={} matchedBets={} topic={} partition={} offset={}",
                            eventId, result.settlementCount(), record.topic(), record.partition(), record.offset());
                    case DUPLICATE -> log.info("Duplicate event outcome suppressed eventId={} winnerId={}",
                            eventId, outcome.eventWinnerId());
                    case CONFLICT -> {
                        // Matching already logs the accepted and rejected winners.
                    }
                }
            }
        } catch (Exception failure) {
            // Retain correlation context here; the container logs the stack and retry attempt.
            log.warn("Outcome processing failed topic={} partition={} offset={} payloadLength={}",
                    record.topic(), record.partition(), record.offset(), message == null ? 0 : message.length());
            if (failure instanceof BetSettlementException settlementFailure) {
                throw settlementFailure;
            }
            throw new BetSettlementException("Failed to process event-outcome message", failure);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
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
