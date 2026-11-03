package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.service.BetSettlementService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MockRocketMqLifecycleTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void successfulDeliveryIsNotRetriedAndCorrelationDoesNotLeakToNextDelivery() throws Exception {
        var broker = new InMemoryBetSettlementBroker();
        var service = mock(BetSettlementService.class);
        var first = command("first", "correlation-first");
        var second = command("second", null);
        var observedContexts = new CopyOnWriteArrayList<String>();
        when(service.settle(any())).thenAnswer(invocation -> {
            observedContexts.add(String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY)));
            return BetSettlementService.Result.SETTLED_WON;
        });
        var consumer = new MockRocketMqConsumer(broker, mapper, service);
        consumer.start();
        try {
            broker.enqueue(mapper.writeValueAsString(first));
            broker.enqueue(mapper.writeValueAsString(second));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(service).settle(second));
        } finally {
            consumer.stop();
        }
        verify(service).settle(first);
        verifyNoMoreInteractions(service);
        assertThat(observedContexts).containsExactly("correlation-first", "null");
        assertThat(broker.pollDelivery(0, TimeUnit.MILLISECONDS)).isNull();
        assertThat(broker.deadLetterCount()).isZero();
        broker.enqueue(mapper.writeValueAsString(first));
        assertThat(broker.pollDelivery(0, TimeUnit.MILLISECONDS).attempt()).isEqualTo(1);
        verifyNoMoreInteractions(service);
    }

    @Test
    void poisonRetryDoesNotBlockReadyWork() throws Exception {
        var broker = new InMemoryBetSettlementBroker();
        var service = mock(BetSettlementService.class);
        var poison = command("poison", null);
        var healthy = command("healthy", null);
        when(service.settle(poison)).thenThrow(new IllegalStateException("poison"));
        var consumer = new MockRocketMqConsumer(broker, mapper, service);
        broker.enqueue(mapper.writeValueAsString(poison));
        broker.enqueue(mapper.writeValueAsString(healthy));
        consumer.start();
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(broker.deadLetterCount()).isEqualTo(1));
        } finally {
            consumer.stop();
        }
        var ordered = inOrder(service);
        ordered.verify(service).settle(poison);
        ordered.verify(service).settle(healthy);
        ordered.verify(service, times(4)).settle(poison);
        verifyNoMoreInteractions(service);
    }

    @Test
    void deliveryReturnedByPollDuringShutdownIsRetainedWithoutAnotherAttempt() throws Exception {
        var broker = mock(InMemoryBetSettlementBroker.class);
        var service = mock(BetSettlementService.class);
        var polled = new CountDownLatch(1);
        var delivery = new InMemoryBetSettlementBroker.Delivery(mapper.writeValueAsString(command("stopped", null)), 1, 0);
        when(broker.pollDelivery(anyLong(), any())).thenAnswer(invocation -> {
            polled.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException expectedShutdown) {
                return delivery;
            }
            throw new AssertionError("Expected interruption");
        });
        var consumer = new MockRocketMqConsumer(broker, mapper, service);
        consumer.start();
        try {
            assertThat(polled.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            consumer.stop();
        }
        verify(broker).returnUnconsumed(delivery);
        verifyNoInteractions(service);
    }

    private static BetSettlementCommandV1 command(String id, String correlation) {
        return new BetSettlementCommandV1(id, "bet", "user", "event", "market",
                "a", BigDecimal.TEN, "a", Instant.now(), correlation);
    }
}
