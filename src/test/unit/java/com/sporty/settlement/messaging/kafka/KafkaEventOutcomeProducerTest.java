package com.sporty.settlement.messaging.kafka;

import com.sporty.settlement.config.KafkaTopicsProperties;
import com.sporty.settlement.exception.EventOutcomePublishException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaEventOutcomeProducerTest {
    @Test
    void asynchronousBrokerFailureIsPropagated() {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaTopicsProperties properties = new KafkaTopicsProperties();
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker unavailable"));
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any())).thenReturn(future);

        var producer = new KafkaEventOutcomeProducer(template, properties, Duration.ofSeconds(1));

        assertThatThrownBy(() -> producer.send("event-100", "{}", "corr"))
                .isInstanceOf(EventOutcomePublishException.class)
                .hasMessageContaining("acknowledge");
    }

    @Test
    void timeoutIsReportedInsteadOfFalseSuccess() {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaTopicsProperties properties = new KafkaTopicsProperties();
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(new CompletableFuture<>());
        var producer = new KafkaEventOutcomeProducer(template, properties, Duration.ofMillis(1));

        assertThatThrownBy(() -> producer.send("event-100", "{}", "corr"))
                .isInstanceOf(EventOutcomePublishException.class);
    }
}
