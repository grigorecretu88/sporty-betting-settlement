package com.sporty.settlement.infrastructure.rocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.application.ports.BetSettlementPublisher;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MockRocketMqPublisher implements BetSettlementPublisher {

    private static final Logger log = LoggerFactory.getLogger(MockRocketMqPublisher.class);

    private final ObjectMapper mapper;

    public MockRocketMqPublisher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void publish(BetSettlementCommandV1 cmd) {
        try {
            // Proper mock: behaves like a producer by logging the payload clearly
            String json = mapper.writeValueAsString(cmd);
            log.info("[rocketmq:bet-settlements] produced {}", json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize settlement command", e);
        }
    }
}
