package com.sporty.settlement.integration;

import com.sporty.settlement.api.EventOutcomeController;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.api.dto.BetResponse;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test
    void fullFlowSettlesOnlyMatchingBetsAndRepeatIsBusinessIdempotent() throws Exception {
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
            assertPublicBetState();
        });

        assertThat(rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(request, headers), Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.setContentType(MediaType.APPLICATION_JSON);
        retryHeaders.set(EventOutcomeController.IDEMPOTENCY_KEY_HEADER, "it-key-2");
        assertThat(rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(request, retryHeaders), Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // A later record on the same partition proves the preceding HTTP retry has been consumed.
        var conflict = kafkaTemplate.send("event-outcomes", "event-100",
                "{\"eventId\":\"event-100\",\"eventName\":\"Derby\",\"eventWinnerId\":\"team-b\"}")
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        String group = listeners.getListenerContainers().iterator().next().getGroupId();
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()))) {
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(new TopicPartition(conflict.topic(), conflict.partition()));
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThan(conflict.offset());
            });
        }
        assertThat(outbox.count()).isEqualTo(2);
        assertPublicBetState();
    }

    private void assertPublicBetState() {
        ResponseEntity<BetResponse[]> visible = rest.getForEntity("/api/bets", BetResponse[].class);
        assertThat(visible.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(visible.getBody()).hasSize(3);
        var byId = java.util.Arrays.stream(visible.getBody()).collect(
                java.util.stream.Collectors.toMap(BetResponse::betId, java.util.function.Function.identity()));
        assertThat(byId.get("bet-1")).satisfies(bet -> {
            assertThat(bet.status()).isEqualTo("SETTLED_WON");
            assertThat(bet.eventId()).isEqualTo("event-100");
            assertThat(bet.eventWinnerId()).isEqualTo("team-a");
            assertThat(bet.outcomeWinnerId()).isEqualTo("team-a");
        });
        assertThat(byId.get("bet-2")).satisfies(bet -> {
            assertThat(bet.status()).isEqualTo("SETTLED_LOST");
            assertThat(bet.eventId()).isEqualTo("event-100");
            assertThat(bet.eventWinnerId()).isEqualTo("team-b");
            assertThat(bet.outcomeWinnerId()).isEqualTo("team-a");
        });
        assertThat(byId.get("bet-3")).satisfies(bet -> {
            assertThat(bet.status()).isEqualTo("PENDING");
            assertThat(bet.eventId()).isEqualTo("event-200");
            assertThat(bet.eventWinnerId()).isEqualTo("team-x");
            assertThat(bet.outcomeWinnerId()).isNull();
            assertThat(bet.settledAt()).isNull();
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
