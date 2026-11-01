package com.sporty.settlement.messaging.rocket.real;

import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.apache.rocketmq.client.producer.SendStatus;
import com.sporty.settlement.exception.BetSettlementException;

/**
 * Real RocketMQ publisher for the "bet-settlements" topic (Use Case 4).
 *
 * Enabled when app.rocketmq.mode=real.
 *
 * Notes:
 * - We publish the domain command as the message payload; rocketmq-spring handles
 *   serialisation using its message converters.
 * - The outbox dispatcher invokes this synchronous send outside its database
 *   transactions, then records the result in a separate transaction.
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
        var builder = MessageBuilder.withPayload(cmd)
                .setHeader("settlementCommandId", cmd.commandId());
        if (cmd.correlationId() != null && !cmd.correlationId().isBlank()) {
            builder.setHeader(com.sporty.settlement.logging.CorrelationIdFilter.HEADER, cmd.correlationId());
        }
        var result = rocketMQTemplate.syncSend(topic, builder.build());
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new BetSettlementException("RocketMQ did not acknowledge command " + cmd.commandId()
                    + "; status=" + (result == null ? "null" : result.getSendStatus()));
        }
        log.info("[rocketmq:{}] acknowledged commandId={} betId={} eventId={} messageId={}",
                topic, cmd.commandId(), cmd.betId(), cmd.eventId(), result.getMsgId());
    }
}
