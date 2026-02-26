package com.sporty.settlement.messaging.rocket.real;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Real RocketMQ publisher for the "bet-settlements" topic (Use Case 4).
 *
 * Enabled when app.rocketmq.mode=real.
 *
 * Notes:
 * - We publish the domain command as the message payload; rocketmq-spring handles
 *   serialisation using its message converters.
 * - The outbox dispatcher invokes this publisher, so publish() must be fast and side-effect free
 *   other than sending the message.
 */
@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "real")
@Slf4j
public class RealRocketMqPublisher implements BetSettlementPublisher {

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public RealRocketMqPublisher(RocketMQTemplate rocketMQTemplate,
                                 org.springframework.core.env.Environment env) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = env.getProperty("app.rocketmq.topics.bet-settlements", "bet-settlements");
    }

    @Override
    public void publish(BetSettlementCommandV1 cmd) {
        // Destination format: "topic" or "topic:tag"
        if (cmd.correlationId() == null || cmd.correlationId().isBlank()) {
            rocketMQTemplate.convertAndSend(topic, cmd);
        } else {
            rocketMQTemplate.send(topic, MessageBuilder.withPayload(cmd)
                    .setHeader(com.sporty.settlement.logging.CorrelationIdFilter.HEADER, cmd.correlationId())
                    .build());
        }
        log.info("[rocketmq:{}] produced betId={} eventId={}", topic, cmd.betId(), cmd.eventId());
    }
}
