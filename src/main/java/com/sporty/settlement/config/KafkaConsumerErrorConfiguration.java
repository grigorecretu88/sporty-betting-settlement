package com.sporty.settlement.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
@Slf4j
public class KafkaConsumerErrorConfiguration {

    @Bean
    CommonErrorHandler eventOutcomeErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.consumer.retry-interval:250ms}") Duration retryInterval,
            @Value("${app.kafka.consumer.max-retries:2}") long maxRetries,
            @Value("${app.kafka.consumer.dlt-publish-timeout:5s}") Duration dltPublishTimeout) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(dltPublishTimeout);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryInterval.toMillis(), maxRetries));
        handler.setRetryListeners((record, failure, deliveryAttempt) ->
                log.warn("Kafka outcome processing attempt failed topic={} partition={} offset={} attempt={}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt, failure));
        return handler;
    }
}
