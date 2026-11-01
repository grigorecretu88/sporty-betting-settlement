package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.service.BetSettlementService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
public class MockRocketMqConsumer {

    private final InMemoryBetSettlementBroker broker;
    private final ObjectMapper mapper;
    private final BetSettlementService settlementService;
    private volatile boolean running = true;

    public MockRocketMqConsumer(InMemoryBetSettlementBroker broker, ObjectMapper mapper,
                                BetSettlementService settlementService) {
        this.broker = broker;
        this.mapper = mapper;
        this.settlementService = settlementService;
    }

    @PostConstruct
    public void start() {
        Thread t = new Thread(this::consumeLoop, "mock-rocketmq-consumer");
        t.setDaemon(true);
        t.start();
        log.info("[rocketmq:bet-settlements] consumer started");
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void consumeLoop() {
        while (running) {
            try {
                String json = broker.poll(1, TimeUnit.SECONDS);
                if (json != null) {
                    consume(json);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[rocketmq:bet-settlements] error in consumer loop", e);
            }
        }
    }

    private void consume(String json) {
        try {
            BetSettlementCommandV1 cmd = mapper.readValue(json, BetSettlementCommandV1.class);
            String correlationId = cmd.correlationId();

            Runnable work = () -> settlementService.settle(cmd);

            if (correlationId == null || correlationId.isBlank()) {
                work.run();
            } else {
                try (MDC.MDCCloseable ignored = MDC.putCloseable(
                        com.sporty.settlement.logging.CorrelationIdFilter.MDC_KEY,
                        correlationId
                )) {
                    work.run();
                }
            }
        } catch (Exception e) {
            log.error("[rocketmq:bet-settlements] failed to settle message payloadLength={}", json.length(), e);
            broker.retry(json);
        }
    }
}
