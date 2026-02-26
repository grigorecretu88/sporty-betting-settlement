package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.messaging.EventOutcomeProducer;
import com.sporty.settlement.domain.EventOutcomeV1;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PublishEventOutcomeService {

    private static final String IDEMP_SCOPE = "api.publish.event-outcome";

    private final EventOutcomeProducer producer;
    private final ObjectMapper mapper;
    private final IdempotencyService idempotency;

    public PublishEventOutcomeService(EventOutcomeProducer producer,
                                     ObjectMapper mapper,
                                     IdempotencyService idempotency) {
        this.producer = producer;
        this.mapper = mapper;
        this.idempotency = idempotency;
    }

    public void publish(PublishEventOutcomeRequest req, String idempotencyKeyHeader, String correlationId) {
        EventOutcomeV1 outcome = new EventOutcomeV1(req.eventId(), req.eventName(), req.eventWinnerId());

        // Prefer explicit Idempotency-Key, fall back to eventId (still prevents accidental duplicates).
        String idKey = (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank())
                ? outcome.eventId()
                : idempotencyKeyHeader.trim();

        idempotency.executeOnce(IDEMP_SCOPE, idKey, () -> {
            try {
                String json = mapper.writeValueAsString(outcome);
                log.info("Publishing event outcome to Kafka eventId={} winnerId={} idKey={}",
                        outcome.eventId(), outcome.eventWinnerId(), idKey);
                producer.send(outcome.eventId(), json, correlationId);
                return true;
            } catch (Exception e) {
                throw new EventOutcomePublishException("Failed to publish event outcome for eventId=" + outcome.eventId(), e);
            }
        });
    }
}
