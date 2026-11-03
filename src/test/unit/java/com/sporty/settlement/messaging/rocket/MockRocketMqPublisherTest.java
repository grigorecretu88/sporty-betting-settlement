package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.exception.BetSettlementException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MockRocketMqPublisherTest {

    @Test
    void transportFailureIsNotMisreportedAsSerializationFailure() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        InMemoryBetSettlementBroker broker = mock(InMemoryBetSettlementBroker.class);
        when(mapper.writeValueAsString(any())).thenReturn("{cmd}");
        var failure = new IllegalStateException("transport unavailable");
        doThrow(failure).when(broker).enqueue("{cmd}");
        var command = command();

        assertThatThrownBy(() -> new MockRocketMqPublisher(mapper, broker).publish(command))
                .isSameAs(failure);
    }

    @Test
    void publishSerializesAndEnqueues() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        InMemoryBetSettlementBroker broker = new InMemoryBetSettlementBroker();
        when(mapper.writeValueAsString(any())).thenReturn("{cmd}");

        MockRocketMqPublisher publisher = new MockRocketMqPublisher(mapper, broker);
        publisher.publish(command());

        assertThat(broker.poll(10, TimeUnit.MILLISECONDS)).isEqualTo("{cmd}");
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
