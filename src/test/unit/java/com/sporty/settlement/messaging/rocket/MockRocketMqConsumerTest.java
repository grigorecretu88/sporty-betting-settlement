package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.service.BetSettlementService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class MockRocketMqConsumerTest {
    @Test void transientFailureIsRedelivered() throws Exception { verifyDelivery(false); }
    @Test void persistentFailureIsRetainedAfterFiveAttempts() throws Exception { verifyDelivery(true); }

    private void verifyDelivery(boolean persistent) throws Exception {
        var broker = new InMemoryBetSettlementBroker();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var service = mock(BetSettlementService.class);
        var command = new BetSettlementCommandV1("cmd", "bet", "user", "event", "market",
                "a", BigDecimal.TEN, "a", Instant.now(), null);
        if (persistent) doThrow(new IllegalStateException("unavailable")).when(service).settle(any());
        // A successful mock return is sufficient: business transitions have their own integration tests.
        if (!persistent) when(service.settle(any())).thenThrow(new IllegalStateException("transient")).thenReturn(null);
        var consumer = new MockRocketMqConsumer(broker, mapper, service);
        consumer.start();
        try {
            broker.enqueue(mapper.writeValueAsString(command));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                verify(service, times(persistent ? 5 : 2)).settle(command);
                assertThat(broker.deadLetterCount()).isEqualTo(persistent ? 1 : 0);
            });
        } finally { consumer.stop(); }
    }
}
