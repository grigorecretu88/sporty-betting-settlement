package com.sporty.settlement.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.service.OutcomeProcessingResult;
import com.sporty.settlement.service.SettlementMatchingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventOutcomeListenerTest {
    @Test
    void businessFailureEscapesUnchangedAndClearsCorrelationContext() {
        var matching = mock(SettlementMatchingService.class);
        var failure = new BetSettlementException("settlement unavailable");
        when(matching.process(any(), anyString())).thenThrow(failure);
        var listener = new EventOutcomeListener(new ObjectMapper(), matching);
        var record = new ConsumerRecord<String, String>("event-outcomes", 0, 7, "event-100",
                "{\"eventId\":\"event-100\",\"eventName\":\"Derby\",\"eventWinnerId\":\"team-a\"}");

        assertThatThrownBy(() -> listener.listen(record)).isSameAs(failure);
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("kafkaKey")).isNull();
    }

    @ParameterizedTest
    @EnumSource(OutcomeProcessingResult.Status.class)
    void acceptedDuplicateAndConflictingOutcomesReturnNormallyAndClearMdc(
            OutcomeProcessingResult.Status status) throws Exception {
        SettlementMatchingService matching = mock(SettlementMatchingService.class);
        when(matching.process(any(), anyString())).thenReturn(
                new OutcomeProcessingResult(status, status == OutcomeProcessingResult.Status.ACCEPTED ? 2 : 0));
        ObjectMapper mapper = new ObjectMapper();
        var listener = new EventOutcomeListener(mapper, matching);
        var record = new ConsumerRecord<String, String>("event-outcomes", 0, 7, "event-100",
                mapper.writeValueAsString(new EventOutcomeV1("event-100", "Derby", "team-a")));

        listener.listen(record);

        verify(matching).process(argThat(o -> o.eventId().equals("event-100")), anyString());
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("kafkaKey")).isNull();
    }

    @Test
    void malformedMessagePropagatesSoKafkaCanRetryOrRecover() {
        var listener = new EventOutcomeListener(new ObjectMapper(), mock(SettlementMatchingService.class));
        var record = new ConsumerRecord<String, String>("event-outcomes", 0, 0, "key", "{bad");
        assertThatThrownBy(() -> listener.listen(record)).isInstanceOf(BetSettlementException.class);
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("kafkaKey")).isNull();
    }
}
