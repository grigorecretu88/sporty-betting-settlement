package com.sporty.settlement.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.service.IdempotencyService;
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
    private final IdempotencyService idempotency;

    public EventOutcomeListener(ObjectMapper mapper,
                                SettlementMatchingService matchingService,
                                IdempotencyService idempotency) {
        this.mapper = mapper;
        this.matchingService = matchingService;
        this.idempotency = idempotency;
    }

    @KafkaListener(topics = "${app.kafka.topics.event-outcomes:event-outcomes}", groupId = "settlement-group")
    public void listen(ConsumerRecord<String, String> record) {
        String message = record.value();
        String correlationId = extractCorrelationId(record)
                .orElseGet(() -> "kafka-" + UUID.randomUUID());

        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId)) {
            try {
                EventOutcomeV1 outcome = mapper.readValue(message, EventOutcomeV1.class);

                // MDC: keep the business key for log readability.
                // Idempotency: use Kafka delivery identity (topic/partition/offset) to avoid suppressing
                // legitimate re-publishes of the same eventId (common in tests and real systems).
                String eventId = outcome.eventId();
                String idempotencyKey = record.topic() + ":" + record.partition() + ":" + record.offset();

                try (MDC.MDCCloseable ignoredKafkaKey = MDC.putCloseable("kafkaKey", eventId)) {
                    Boolean processed = idempotency.executeOnce("kafka.consume.event-outcome", idempotencyKey, () -> {
                        int matched = matchingService.matchAndEnqueue(outcome, correlationId);
                        log.info("Consumed event-outcome eventId={} matchedBets={} (outbox enqueued)", outcome.eventId(), matched);
                        return Boolean.TRUE;
                    });
                    if (processed == null) {
                        log.info("Duplicate event-outcome suppressed eventId={}", outcome.eventId());
                    }
                }
            } catch (BetSettlementException e) {
                log.error("Settlement processing failed payload={}", message, e);
                throw e;
            } catch (Exception e) {
                log.error("Failed to process event-outcome payload={}", message, e);
                throw new BetSettlementException("Failed to process event-outcome message", e);
            }
        }
    }

    private static Optional<String> extractCorrelationId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(CorrelationIdFilter.HEADER);
        if (header == null || header.value() == null) {
            return Optional.empty();
        }
        String value = new String(header.value(), StandardCharsets.UTF_8).trim();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
