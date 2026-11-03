package com.sporty.settlement.integration;

import com.sporty.settlement.api.EventOutcomeController;
import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.messaging.kafka.EventOutcomeListener;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.kafka.consumer.max-retries=2",
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
    @Autowired ProcessedEventOutcomeRepository outcomes;
    @SpyBean EventOutcomeListener outcomeListener;

    @Test
    void httpIdempotencyControlsKafkaPublicationWhileBusinessProcessingRemainsIdempotent() throws Exception {
        HttpHeaders firstKeyHeaders = outcomeHeaders("it-key-1", "it-corr-1");
        var request = new PublishEventOutcomeRequest("event-100", "Derby", "team-a");
        long initialEndOffset = eventOutcomeEndOffset();

        ResponseEntity<Void> response = rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(request, firstKeyHeaders), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long firstPublicationEndOffset = eventOutcomeEndOffset();
        assertThat(firstPublicationEndOffset).isEqualTo(initialEndOffset + 1);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(bets.findById("bet-1").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_WON);
            assertThat(bets.findById("bet-2").orElseThrow().getStatus()).isEqualTo(BetStatus.SETTLED_LOST);
            assertThat(bets.findById("bet-3").orElseThrow().getStatus()).isEqualTo(BetStatus.PENDING);
            assertThat(outbox.countByStatus(OutboxStatus.SENT)).isEqualTo(2);
            assertThat(outbox.findAll()).extracting(OutboxMessageEntity::getCorrelationId)
                    .containsOnly("it-corr-1");
            assertPublicBetState();
        });
        Instant winnerSettledAt = bets.findById("bet-1").orElseThrow().getSettledAt();
        Instant loserSettledAt = bets.findById("bet-2").orElseThrow().getSettledAt();

        assertThat(rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(request, firstKeyHeaders), Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(eventOutcomeEndOffset()).isEqualTo(firstPublicationEndOffset);
        assertSettlementUnchanged(winnerSettledAt, loserSettledAt);

        HttpHeaders secondKeyHeaders = outcomeHeaders("it-key-2", "it-corr-2");
        assertThat(rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(request, secondKeyHeaders), Void.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long secondPublicationEndOffset = eventOutcomeEndOffset();
        assertThat(secondPublicationEndOffset).isEqualTo(firstPublicationEndOffset + 1);
        awaitConsumerPast(secondPublicationEndOffset - 1);
        assertSettlementUnchanged(winnerSettledAt, loserSettledAt);

        var conflictingPayload = new PublishEventOutcomeRequest("event-100", "Renamed Derby", "team-a");
        assertThat(rest.postForEntity("/api/event-outcomes",
                new HttpEntity<>(conflictingPayload, firstKeyHeaders), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(eventOutcomeEndOffset()).isEqualTo(secondPublicationEndOffset);
        assertSettlementUnchanged(winnerSettledAt, loserSettledAt);

        // Bypass HTTP claims so the conflicting winner reaches the actual Kafka listener.
        var conflict = kafkaTemplate.send("event-outcomes", "event-100",
                        "{\"eventId\":\"event-100\",\"eventName\":\"Conflicting Derby\",\"eventWinnerId\":\"team-b\"}")
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        awaitConsumerPast(conflict.offset());
        verify(outcomeListener).listen(argThat(record ->
                record.topic().equals(conflict.topic())
                        && record.partition() == conflict.partition()
                        && record.offset() == conflict.offset()));
        assertSettlementUnchanged(winnerSettledAt, loserSettledAt);
        assertThat(outcomes.findById("event-100").orElseThrow()).satisfies(outcome -> {
            assertThat(outcome.getEventName()).isEqualTo("Derby");
            assertThat(outcome.getWinnerId()).isEqualTo("team-a");
        });
        assertPublicBetState();
    }

    private HttpHeaders outcomeHeaders(String idempotencyKey, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(CorrelationIdFilter.HEADER, correlationId);
        headers.set(EventOutcomeController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        return headers;
    }

    private long eventOutcomeEndOffset() throws Exception {
        TopicPartition partition = new TopicPartition("event-outcomes", 0);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()))) {
            return admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                    .all().get(5, TimeUnit.SECONDS).get(partition).offset();
        }
    }

    private void awaitConsumerPast(long recordOffset) throws Exception {
        String group = listeners.getListenerContainers().iterator().next().getGroupId();
        TopicPartition partition = new TopicPartition("event-outcomes", 0);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()))) {
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(partition);
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThan(recordOffset);
            });
        }
    }

    private void assertSettlementUnchanged(Instant winnerSettledAt, Instant loserSettledAt) {
        assertThat(outbox.count()).isEqualTo(2);
        assertThat(outbox.countByStatus(OutboxStatus.SENT)).isEqualTo(2);
        assertThat(bets.findById("bet-1").orElseThrow()).satisfies(bet -> {
            assertThat(bet.getStatus()).isEqualTo(BetStatus.SETTLED_WON);
            assertThat(bet.getOutcomeWinnerId()).isEqualTo("team-a");
            assertThat(bet.getSettledAt()).isEqualTo(winnerSettledAt);
        });
        assertThat(bets.findById("bet-2").orElseThrow()).satisfies(bet -> {
            assertThat(bet.getStatus()).isEqualTo(BetStatus.SETTLED_LOST);
            assertThat(bet.getOutcomeWinnerId()).isEqualTo("team-a");
            assertThat(bet.getSettledAt()).isEqualTo(loserSettledAt);
        });
    }

    private void assertPublicBetState() {
        ResponseEntity<BetResponse[]> visible = rest.getForEntity("/api/bets", BetResponse[].class);
        assertThat(visible.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(visible.getBody()).hasSize(3);
        var byId = Arrays.stream(visible.getBody()).collect(
                Collectors.toMap(BetResponse::betId, Function.identity()));
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
    void poisonOutcomeIsAttemptedThreeTimesDeadLetteredAndDoesNotBlockHealthyWork() throws Exception {
        clearInvocations(outcomeListener);
        try (Consumer<String, String> dltConsumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("dlt-verifier", "false", embeddedKafka),
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            dltConsumer.subscribe(List.of("event-outcomes.DLT"));
            kafkaTemplate.send("event-outcomes", "broken-event", "{not-json")
                    .get(10, TimeUnit.SECONDS);

            var deadLetter = KafkaTestUtils.getSingleRecord(
                    dltConsumer, "event-outcomes.DLT", Duration.ofSeconds(15));
            assertThat(deadLetter.key()).isEqualTo("broken-event");
            assertThat(deadLetter.value()).isEqualTo("{not-json");

            verify(outcomeListener, times(3)).listen(argThat(record ->
                    "broken-event".equals(record.key()) && "{not-json".equals(record.value())));

            kafkaTemplate.send("event-outcomes", "healthy-event",
                            "{\"eventId\":\"healthy-event\",\"eventName\":\"Healthy\",\"eventWinnerId\":\"team-a\"}")
                    .get(10, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(outcomes.findById("healthy-event")).isPresent());
            verify(outcomeListener).listen(argThat(record -> "healthy-event".equals(record.key())));
        }
    }
}
