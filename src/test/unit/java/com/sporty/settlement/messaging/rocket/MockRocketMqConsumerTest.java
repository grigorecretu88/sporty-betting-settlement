package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.service.BetSettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class MockRocketMqConsumerTest {
    private final InMemoryBetSettlementBroker broker = new InMemoryBetSettlementBroker();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final BetSettlementService service = mock(BetSettlementService.class);
    private final MockRocketMqConsumer consumer = new MockRocketMqConsumer(broker, mapper, service);

    @AfterEach
    void stopConsumer() {
        consumer.stop();
    }

    @Test
    void transientFailureIsRedelivered() throws Exception {
        var command = command();
        when(service.settle(any())).thenThrow(new IllegalStateException("transient")).thenReturn(null);

        consumer.start();
        broker.enqueue(mapper.writeValueAsString(command));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(service, times(2)).settle(command);
            assertThat(broker.deadLetterCount()).isZero();
        });
    }

    @Test
    void persistentFailureIsRetainedAfterFiveAttempts() throws Exception {
        var command = command();
        doThrow(new IllegalStateException("unavailable")).when(service).settle(any());

        consumer.start();
        broker.enqueue(mapper.writeValueAsString(command));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(service, times(5)).settle(command);
            assertThat(broker.deadLetterCount()).isEqualTo(1);
        });
    }

    private static BetSettlementCommandV1 command() {
        return new BetSettlementCommandV1("cmd", "bet", "user", "event", "market",
                "a", BigDecimal.TEN, "a", Instant.now(), null);
    }
}
