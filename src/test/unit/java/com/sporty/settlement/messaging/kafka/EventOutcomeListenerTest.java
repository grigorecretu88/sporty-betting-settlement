package com.sporty.settlement.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.messaging.kafka.EventOutcomeListener;
import com.sporty.settlement.service.IdempotencyService;
import com.sporty.settlement.service.SettlementMatchingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventOutcomeListenerTest {

    @Test
    void listen_happyPath_callsIdempotencyAndMatching_andCleansMdc() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SettlementMatchingService matching = mock(SettlementMatchingService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);

        // Idempotency key is Kafka delivery identity: topic:partition:offset
        when(idempotency.executeOnce(eq("kafka.consume.event-outcome"), eq("event-outcomes:0:0"), any())).thenAnswer(inv -> {
            return ((java.util.function.Supplier<?>) inv.getArgument(2)).get();
        });
        when(matching.matchAndEnqueue(any(), anyString())).thenReturn(2);

        EventOutcomeListener listener = new EventOutcomeListener(mapper, matching, idempotency);
        String json = mapper.writeValueAsString(new EventOutcomeV1("E1", "Event", "W1"));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("event-outcomes", 0, 0L, "E1", json);
        record.headers().add(new RecordHeader(
                com.sporty.settlement.logging.CorrelationIdFilter.HEADER,
                "corr-1".getBytes(StandardCharsets.UTF_8)
        ));

        listener.listen(record);

        verify(matching).matchAndEnqueue(argThat(o -> o.eventId().equals("E1")), eq("corr-1"));
        assertThat(MDC.get("kafkaKey")).isNull();
        assertThat(MDC.get(com.sporty.settlement.logging.CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void listen_whenDuplicate_doesNotCallMatching() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SettlementMatchingService matching = mock(SettlementMatchingService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.executeOnce(anyString(), anyString(), any())).thenReturn(null);

        EventOutcomeListener listener = new EventOutcomeListener(mapper, matching, idempotency);
        String json = mapper.writeValueAsString(new EventOutcomeV1("E1", "Event", "W1"));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("event-outcomes", 0, 0L, "E1", json);

        listener.listen(record);

        verifyNoInteractions(matching);
        assertThat(MDC.get("kafkaKey")).isNull();
        assertThat(MDC.get(com.sporty.settlement.logging.CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void listen_whenInvalidJson_wrapsAsBetSettlementException_andCleansMdc() {
        ObjectMapper mapper = new ObjectMapper();
        SettlementMatchingService matching = mock(SettlementMatchingService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        EventOutcomeListener listener = new EventOutcomeListener(mapper, matching, idempotency);

        ConsumerRecord<String, String> record = new ConsumerRecord<>("event-outcomes", 0, 0L, "k", "{not-json");

        assertThatThrownBy(() -> listener.listen(record))
                .isInstanceOf(BetSettlementException.class)
                .hasMessageContaining("event-outcome");
        assertThat(MDC.get("kafkaKey")).isNull();
        assertThat(MDC.get(com.sporty.settlement.logging.CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
