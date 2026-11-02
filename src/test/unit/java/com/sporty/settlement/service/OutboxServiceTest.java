package com.sporty.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxServiceTest {

    @Test
    void enqueue_rejectsNullCommand() {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        OutboxService svc = new OutboxService(repo, mapper);

        assertThatThrownBy(() -> svc.enqueue(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    void enqueue_throwsHelpfulErrorWhenSerializationFails() throws Exception {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        OutboxService svc = new OutboxService(repo, mapper);

        assertThatThrownBy(() -> svc.enqueue(command()))
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

        String id = svc.enqueue(command());
        assertThat(id).isEqualTo("command-1");

        ArgumentCaptor<OutboxMessageEntity> captor = ArgumentCaptor.forClass(OutboxMessageEntity.class);
        verify(repo).save(captor.capture());
        OutboxMessageEntity saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getDestination()).isEqualTo("bet-settlements");
        assertThat(saved.getMessageType()).isEqualTo("BetSettlementCommandV1");
        assertThat(saved.getPayloadJson()).isEqualTo("{\"ok\":true}");
        assertThat(saved.getCorrelationId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    private static BetSettlementCommandV1 command() {
        return new BetSettlementCommandV1("command-1", "bet", "user", "event", "market",
                "a", BigDecimal.TEN, "b", Instant.EPOCH, null);
    }
}
