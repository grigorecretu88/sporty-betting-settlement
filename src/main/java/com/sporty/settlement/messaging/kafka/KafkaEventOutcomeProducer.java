package com.sporty.settlement.messaging.kafka;

import com.sporty.settlement.config.KafkaTopicsProperties;
import com.sporty.settlement.messaging.EventOutcomeProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@Slf4j
public class KafkaEventOutcomeProducer implements EventOutcomeProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    public KafkaEventOutcomeProducer(KafkaTemplate<String, String> kafkaTemplate,
                                    KafkaTopicsProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    @Override
    public void send(String key, String payload, String correlationId) {
        String topic = topics.getTopics().getEventOutcomes();
        log.debug("Sending event outcome to topic={} key={} payloadSize={}",
                topic, key, payload == null ? 0 : payload.length());

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        Optional.ofNullable(correlationId)
                .filter(v -> !v.isBlank())
                .ifPresent(v -> record.headers().add(new RecordHeader(
                        com.sporty.settlement.logging.CorrelationIdFilter.HEADER,
                        v.getBytes(StandardCharsets.UTF_8)
                )));

        kafkaTemplate.send(record);
    }
}
