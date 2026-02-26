package com.sporty.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxServiceTest {

    @Test
    void enqueue_rejectsBlankDestination() {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        OutboxService svc = new OutboxService(repo, mapper);

        assertThatThrownBy(() -> svc.enqueue(" ", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination");
    }

    @Test
    void enqueue_rejectsNullPayload() {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        OutboxService svc = new OutboxService(repo, mapper);

        assertThatThrownBy(() -> svc.enqueue("bet-settlements", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void enqueue_throwsHelpfulErrorWhenSerializationFails() throws Exception {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        OutboxService svc = new OutboxService(repo, mapper);

        assertThatThrownBy(() -> svc.enqueue("bet-settlements", new EventOutcomeV1("E1", "Event", "W1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serialize");
        verify(repo, never()).save(any());
    }

    @Test
    void enqueue_persistsNewOutboxRecord() throws Exception {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");
        OutboxService svc = new OutboxService(repo, mapper);

        String id = svc.enqueue("bet-settlements", new EventOutcomeV1("E1", "Event", "W1"));
        assertThat(id).isNotBlank();

        ArgumentCaptor<OutboxMessageEntity> captor = ArgumentCaptor.forClass(OutboxMessageEntity.class);
        verify(repo).save(captor.capture());
        OutboxMessageEntity saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getDestination()).isEqualTo("bet-settlements");
        assertThat(saved.getMessageType()).isEqualTo("EventOutcomeV1");
        assertThat(saved.getPayloadJson()).isEqualTo("{\"ok\":true}");
        assertThat(saved.getCorrelationId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}
