package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockRocketMqPublisher implements BetSettlementPublisher {

    private final ObjectMapper mapper;
    private final InMemoryBetSettlementBroker broker;

    public MockRocketMqPublisher(ObjectMapper mapper, InMemoryBetSettlementBroker broker) {
        this.mapper = mapper;
        this.broker = broker;
    }

    @Override
    public void publish(BetSettlementCommandV1 cmd) {
        try {
            String json = mapper.writeValueAsString(cmd);
            log.info("[rocketmq:bet-settlements] produced betId={} eventId={}", cmd.betId(), cmd.eventId());
            broker.enqueue(json);
        } catch (Exception e) {
            throw new BetSettlementException("Failed to serialize settlement command for betId=" + cmd.betId(), e);
        }
    }
}
