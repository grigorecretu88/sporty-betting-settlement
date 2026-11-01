package com.sporty.settlement.integration;

import com.sporty.settlement.api.EventOutcomeController;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.outbox.dispatcher.delay-ms=100"})
@EmbeddedKafka(partitions = 1, topics = {"event-outcomes", "event-outcomes.DLT"})
@ActiveProfiles({"integration", "mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventOutcomeFlowIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired BetRepository bets;
    @Autowired OutboxMessageRepository outbox;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker embeddedKafka;

    @Test
    void fullFlowSettlesOnlyMatchingBetsAndRepeatIsBusinessIdempotent() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "it-corr-1");
        headers.set(EventOutcomeController.IDEMPOTENCY_KEY_HEADER, "it-key-1");
        var request = new PublishEventOutcomeRequest("event-100", "Derby", "team-a");

        ResponseEntity<Void> response = rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(request, headers), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(bets.findById("bet-1").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_WON);
            assertThat(bets.findById("bet-2").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_LOST);
            assertThat(bets.findById("bet-3").orElseThrow().getStatus()).isEqualTo(BetStatus.PENDING);
            assertThat(outbox.countByStatus(OutboxStatus.SENT)).isEqualTo(2);
        });

        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.setContentType(MediaType.APPLICATION_JSON);
        retryHeaders.set(EventOutcomeController.IDEMPOTENCY_KEY_HEADER, "it-key-2");
        assertThat(rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(request, retryHeaders), Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(outbox.count()).isEqualTo(2);
            assertThat(bets.findById("bet-1").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_WON);
            assertThat(bets.findById("bet-2").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_LOST);
            assertThat(bets.findById("bet-3").orElseThrow().getStatus()).isEqualTo(BetStatus.PENDING);
        });
    }

    @Test
    void poisonOutcomeIsRetriedThenPublishedToVisibleDeadLetterTopic() {
        Consumer<String, String> dltConsumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("dlt-verifier", "false", embeddedKafka),
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        try {
            dltConsumer.subscribe(Collections.singleton("event-outcomes.DLT"));
            kafkaTemplate.send("event-outcomes", "broken-event", "{not-json");

            var deadLetter = KafkaTestUtils.getSingleRecord(
                    dltConsumer, "event-outcomes.DLT", Duration.ofSeconds(15));
            assertThat(deadLetter.key()).isEqualTo("broken-event");
            assertThat(deadLetter.value()).isEqualTo("{not-json");
        } finally {
            dltConsumer.close();
        }
    }
}
