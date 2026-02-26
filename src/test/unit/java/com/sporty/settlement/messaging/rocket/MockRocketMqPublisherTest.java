package com.sporty.settlement.messaging.rocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.messaging.rocket.InMemoryBetSettlementBroker;
import com.sporty.settlement.messaging.rocket.MockRocketMqPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MockRocketMqPublisherTest {

    @Test
    void publish_serializesAndEnqueues() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        InMemoryBetSettlementBroker broker = new InMemoryBetSettlementBroker();
        when(mapper.writeValueAsString(any())).thenReturn("{cmd}");

        MockRocketMqPublisher publisher = new MockRocketMqPublisher(mapper, broker);
        publisher.publish(new BetSettlementCommandV1("B1", "U1", "E1", "M1", "W1", new BigDecimal("10"), "W2", Instant.now(), "corr-1"));

        assertThat(broker.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo("{cmd}");
    }

    @Test
    void publish_whenSerializationFails_wrapsAsBetSettlementException() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        InMemoryBetSettlementBroker broker = mock(InMemoryBetSettlementBroker.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

        MockRocketMqPublisher publisher = new MockRocketMqPublisher(mapper, broker);

        assertThatThrownBy(() -> publisher.publish(new BetSettlementCommandV1("B1", "U1", "E1", "M1", "W1", new BigDecimal("10"), "W2", Instant.now(), "corr-1")))
                .isInstanceOf(BetSettlementException.class)
                .hasMessageContaining("betId=B1");
        verifyNoInteractions(broker);
    }
}
