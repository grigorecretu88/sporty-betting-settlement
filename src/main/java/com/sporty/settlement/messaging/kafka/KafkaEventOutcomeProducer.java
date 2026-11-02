package com.sporty.settlement.messaging.kafka;

import com.sporty.settlement.config.KafkaTopicsProperties;
import com.sporty.settlement.messaging.EventOutcomeProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.sporty.settlement.exception.EventOutcomePublishException;

@Component
@Slf4j
public class KafkaEventOutcomeProducer implements EventOutcomeProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicsProperties topics;
    private final Duration acknowledgementTimeout;

    public KafkaEventOutcomeProducer(KafkaTemplate<String, String> kafkaTemplate,
                                    KafkaTopicsProperties topics,
                                    @Value("${app.kafka.publish-timeout:5s}") Duration acknowledgementTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.acknowledgementTimeout = acknowledgementTimeout;
    }

    @Override
    public void send(String key, String payload, String correlationId) {
        String topic = topics.getEventOutcomes();
        log.debug("Sending event outcome to topic={} key={} payloadSize={}",
                topic, key, payload == null ? 0 : payload.length());

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        Optional.ofNullable(correlationId)
                .filter(v -> !v.isBlank())
                .ifPresent(v -> record.headers().add(new RecordHeader(
                        com.sporty.settlement.logging.CorrelationIdFilter.HEADER,
                        v.getBytes(StandardCharsets.UTF_8)
                )));

        try {
            var result = kafkaTemplate.send(record).get(acknowledgementTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Kafka acknowledged event outcome topic={} key={} partition={} offset={}", topic, key,
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EventOutcomePublishException("Interrupted while awaiting Kafka acknowledgement", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new EventOutcomePublishException("Kafka did not acknowledge event outcome within "
                    + acknowledgementTimeout, failure);
        }
    }
}
