package com.sporty.settlement.messaging.rocket.real;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.sporty.settlement.service.BetSettlementService;

/**
 * Real RocketMQ transport adapter. Business validation, idempotency and the
 * persisted state transition belong to {@link BetSettlementService}.
 */
@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "real")
@RocketMQMessageListener(
        topic = "${app.rocketmq.topics.bet-settlements:bet-settlements}",
        consumerGroup = "${app.rocketmq.consumer.group:sporty-settlement-consumer}"
)
@Slf4j
public class RealRocketMqConsumer implements RocketMQListener<BetSettlementCommandV1> {

    private final BetSettlementService settlementService;

    public RealRocketMqConsumer(BetSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Override
    public void onMessage(BetSettlementCommandV1 cmd) {
        String correlationId = cmd.correlationId();
        if (correlationId == null || correlationId.isBlank()) {
            settlementService.settle(cmd);
            return;
        }

        try (MDC.MDCCloseable ignored = MDC.putCloseable(
                com.sporty.settlement.logging.CorrelationIdFilter.MDC_KEY,
                correlationId
        )) {
            settlementService.settle(cmd);
        }
    }
}
