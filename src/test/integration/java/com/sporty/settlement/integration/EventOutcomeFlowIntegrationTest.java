package com.sporty.settlement.integration;

import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.service.OutboxDispatcher;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Ensure the app uses the EmbeddedKafka broker when running via Gradle (no local Kafka required).
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",

                // Avoids cross-test interference when Gradle runs tests in parallel / reuses JVMs.
                "spring.kafka.consumer.group-id=settlement-it-${random.uuid}",
                "spring.kafka.consumer.auto-offset-reset=earliest"}
)
@EmbeddedKafka(partitions = 1, topics = {"event-outcomes"})
@ActiveProfiles({"integration","mock"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventOutcomeFlowIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    OutboxMessageRepository outboxRepo;

    @Autowired
    OutboxDispatcher dispatcher;

    @Test
    void httpToKafkaToOutbox_thenManualDispatchMarksSent() {
        // BetSeedData seeds two bets for event-100. Publishing outcome for event-100 should enqueue 2 settlements.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Correlation-Id", "it-corr-1");
        headers.add("X-Idempotency-Key", "it-key-1");

        rest.postForEntity(
                "/api/event-outcomes",
                new HttpEntity<>(new PublishEventOutcomeRequest("event-100", "Derby", "team-a"), headers),
                Object.class
        );

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(outboxRepo.countByStatus(OutboxStatus.NEW))
                            .as("outbox NEW messages")
                            .isGreaterThanOrEqualTo(2);
                });

        // Dispatch (simulate background publisher)
        dispatcher.dispatch();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(outboxRepo.countByStatus(OutboxStatus.SENT))
                            .as("outbox SENT messages")
                            .isGreaterThanOrEqualTo(2);
                });
    }
}
