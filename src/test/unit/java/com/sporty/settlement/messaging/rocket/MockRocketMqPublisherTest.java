package com.sporty.settlement.messaging.rocket;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.exception.BetSettlementException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MockRocketMqPublisherTest {

    @Test
    void transportFailureIsNotMisreportedAsSerializationFailure() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        InMemoryBetSettlementBroker broker = mock(InMemoryBetSettlementBroker.class);
        when(mapper.writeValueAsString(any())).thenReturn("{cmd}");
        var failure = new IllegalStateException("transport unavailable");
        doThrow(failure).when(broker).enqueue("{cmd}");
        var command = command();
        Logger logger = (Logger) LoggerFactory.getLogger(MockRocketMqPublisher.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);

        try {
            assertThatThrownBy(() -> new MockRocketMqPublisher(mapper, broker).publish(command))
                    .isSameAs(failure);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("produced"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void publishSerializesAndEnqueues() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        InMemoryBetSettlementBroker broker = new InMemoryBetSettlementBroker();
        when(mapper.writeValueAsString(any())).thenReturn("{cmd}");

        MockRocketMqPublisher publisher = new MockRocketMqPublisher(mapper, broker);
        publisher.publish(command());

        assertThat(broker.pollDelivery(10, TimeUnit.MILLISECONDS).json()).isEqualTo("{cmd}");
    }

    @Test
    void serializationFailureIsWrappedAsBetSettlementException() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        InMemoryBetSettlementBroker broker = mock(InMemoryBetSettlementBroker.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

        MockRocketMqPublisher publisher = new MockRocketMqPublisher(mapper, broker);

        assertThatThrownBy(() -> publisher.publish(command()))
                .isInstanceOf(BetSettlementException.class)
                .hasMessageContaining("betId=B1");
        verifyNoInteractions(broker);
    }

    private static BetSettlementCommandV1 command() {
        return new BetSettlementCommandV1("C1", "B1", "U1", "E1", "M1", "W1",
                BigDecimal.TEN, "W2", Instant.now(), "corr-1");
    }
}
