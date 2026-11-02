package com.sporty.settlement.messaging.rocket.real;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.logging.CorrelationIdContext;
import com.sporty.settlement.service.BetSettlementService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
@RequiredArgsConstructor
public class RealRocketMqConsumer implements RocketMQListener<BetSettlementCommandV1> {

    private final BetSettlementService settlementService;

    @Override
    public void onMessage(BetSettlementCommandV1 command) {
        CorrelationIdContext.run(command.correlationId(), () -> settlementService.settle(command));
    }
}
