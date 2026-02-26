package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.messaging.EventOutcomeProducer;
import com.sporty.settlement.service.IdempotencyService;
import com.sporty.settlement.service.PublishEventOutcomeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublishEventOutcomeServiceTest {

    @Test
    void publish_usesExplicitIdempotencyKeyHeader_whenProvided() throws Exception {
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);

        when(mapper.writeValueAsString(any())).thenReturn("{json}");
        when(idempotency.executeOnce(anyString(), anyString(), any())).thenAnswer(inv -> {
            // Execute supplier
            return ((java.util.function.Supplier<?>) inv.getArgument(2)).get();
        });

        PublishEventOutcomeService svc = new PublishEventOutcomeService(producer, mapper, idempotency);
        svc.publish(new PublishEventOutcomeRequest("E1", "Event", "W1"), "  key-123 ", "corr-1");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotency).executeOnce(eq("api.publish.event-outcome"), keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).isEqualTo("key-123");
        verify(producer).send("E1", "{json}", "corr-1");
    }

    @Test
    void publish_fallsBackToEventId_whenIdempotencyKeyHeaderMissing() throws Exception {
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);

        when(mapper.writeValueAsString(any())).thenReturn("{json}");
        when(idempotency.executeOnce(anyString(), anyString(), any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(2)).get()
        );

        PublishEventOutcomeService svc = new PublishEventOutcomeService(producer, mapper, idempotency);
        svc.publish(new PublishEventOutcomeRequest("E99", "Event", "W1"), null, "corr-9");

        verify(idempotency).executeOnce(eq("api.publish.event-outcome"), eq("E99"), any());
        verify(producer).send("E99", "{json}", "corr-9");
    }

    @Test
    void publish_whenDuplicate_doesNotPublishToKafka() {
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);

        // Duplicate -> null result
        when(idempotency.executeOnce(anyString(), anyString(), any())).thenReturn(null);

        PublishEventOutcomeService svc = new PublishEventOutcomeService(producer, mapper, idempotency);
        svc.publish(new PublishEventOutcomeRequest("E1", "Event", "W1"), "k", "corr-1");

        verifyNoInteractions(producer);
        verifyNoInteractions(mapper);
    }

    @Test
    void publish_wrapsExceptionsAsEventOutcomePublishException() throws Exception {
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);

        when(idempotency.executeOnce(anyString(), anyString(), any())).thenAnswer(inv -> {
            return ((java.util.function.Supplier<?>) inv.getArgument(2)).get();
        });
        when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));

        PublishEventOutcomeService svc = new PublishEventOutcomeService(producer, mapper, idempotency);

        assertThatThrownBy(() -> svc.publish(new PublishEventOutcomeRequest("E1", "Event", "W1"), "k", "corr-1"))
                .isInstanceOf(EventOutcomePublishException.class)
                .hasMessageContaining("eventId=E1");
    }
}
