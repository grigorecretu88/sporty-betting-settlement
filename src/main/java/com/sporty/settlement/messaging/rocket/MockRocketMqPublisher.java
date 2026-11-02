package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class MockRocketMqPublisher implements BetSettlementPublisher {

    private final ObjectMapper mapper;
    private final InMemoryBetSettlementBroker broker;

    @Override
    public void publish(BetSettlementCommandV1 cmd) {
        final String json;
        try {
            json = mapper.writeValueAsString(cmd);
        } catch (JsonProcessingException e) {
            throw new BetSettlementException("Failed to serialize settlement command for betId=" + cmd.betId(), e);
        }
        log.info("[rocketmq:bet-settlements] produced commandId={} payload={}", cmd.commandId(), json);
        broker.enqueue(json);
    }
}
