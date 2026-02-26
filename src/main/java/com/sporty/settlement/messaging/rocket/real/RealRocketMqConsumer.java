package com.sporty.settlement.messaging.rocket.real;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real RocketMQ consumer for the "bet-settlements" topic (Use Case 5).
 *
 * Enabled when app.rocketmq.mode=real.
 *
 * Minimal "settlement" per assignment:
 * - Consume the message
 * - Compute won/lost by comparing predicted winner vs outcome winner
 * - Log the result (persistence beyond that is optional for the exercise)
 */
@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "real")
@RocketMQMessageListener(
        topic = "${app.rocketmq.topics.bet-settlements:bet-settlements}",
        consumerGroup = "${app.rocketmq.consumer.group:sporty-settlement-consumer}"
)
@Slf4j
public class RealRocketMqConsumer implements RocketMQListener<BetSettlementCommandV1> {

    @Override
    public void onMessage(BetSettlementCommandV1 cmd) {
        String correlationId = cmd.correlationId();
        if (correlationId == null || correlationId.isBlank()) {
            boolean won = cmd.eventWinnerId().equals(cmd.outcomeWinnerId());
            log.info("[rocketmq:bet-settlements] settled betId={} userId={} eventId={} won={} amount={}",
                    cmd.betId(), cmd.userId(), cmd.eventId(), won, cmd.betAmount());
            return;
        }

        try (MDC.MDCCloseable ignored = MDC.putCloseable(
                com.sporty.settlement.logging.CorrelationIdFilter.MDC_KEY,
                correlationId
        )) {
            boolean won = cmd.eventWinnerId().equals(cmd.outcomeWinnerId());
            log.info("[rocketmq:bet-settlements] settled betId={} userId={} eventId={} won={} amount={}",
                    cmd.betId(), cmd.userId(), cmd.eventId(), won, cmd.betAmount());
        }
    }
}
