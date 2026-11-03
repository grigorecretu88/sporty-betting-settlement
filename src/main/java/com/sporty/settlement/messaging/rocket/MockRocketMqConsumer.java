package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.logging.CorrelationIdContext;
import com.sporty.settlement.service.BetSettlementService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Mock RocketMQ consumer for the "bet-settlements" topic (Use Case 5).
 *
 * Drains {@link InMemoryBetSettlementBroker} on a background daemon thread and
 * settles each bet by comparing the user's predicted winner against the actual
 * event outcome winner.
 */
@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class MockRocketMqConsumer {

    private final InMemoryBetSettlementBroker broker;
    private final ObjectMapper mapper;
    private final BetSettlementService settlementService;
    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    public void start() {
        worker = new Thread(this::consumeLoop, "mock-rocketmq-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("[rocketmq:bet-settlements] consumer started");
    }

    @PreDestroy
    public void stop() {
        // Stop fetching new work; an in-flight settlement may finish during the bounded join.
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5000);
                if (worker.isAlive()) {
                    log.warn("Mock settlement consumer did not stop within five seconds");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void consumeLoop() {
        while (running) {
            try {
                var delivery = broker.pollDelivery(1, TimeUnit.SECONDS);
                if (delivery == null) {
                    continue;
                }
                if (!running) {
                    broker.returnUnconsumed(delivery);
                    break;
                }
                consume(delivery);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                log.error("[rocketmq:bet-settlements] error in consumer loop", failure);
            }
        }
    }

    private void consume(InMemoryBetSettlementBroker.Delivery delivery) {
        String json = delivery.json();
        try {
            BetSettlementCommandV1 command = mapper.readValue(json, BetSettlementCommandV1.class);
            CorrelationIdContext.run(command.correlationId(), () -> settlementService.settle(command));
        } catch (Exception failure) {
            log.error("[rocketmq:bet-settlements] failed to settle message payloadLength={}",
                    json.length(), failure);
            if (!broker.retry(delivery)) {
                log.error("[rocketmq:bet-settlements] terminal delivery failure after {} attempts", delivery.attempt());
            }
        }
    }
}
