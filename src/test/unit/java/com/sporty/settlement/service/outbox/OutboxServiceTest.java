package com.sporty.settlement.service.outbox;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxServiceTest {

    @Test
    void enqueueRejectsNullCommand() {
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        OutboxService service = new OutboxService(repository, mapper);

        assertThatThrownBy(() -> service.enqueue(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    void enqueueThrowsHelpfulErrorWhenSerializationFails() throws Exception {
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        OutboxService service = new OutboxService(repository, mapper);

        assertThatThrownBy(() -> service.enqueue(command()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serialize");
        verify(repository, never()).save(any());
    }

    @Test
    void enqueuePersistsNewOutboxRecord() throws Exception {
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");
        OutboxService service = new OutboxService(repository, mapper);

        service.enqueue(command());

        ArgumentCaptor<OutboxMessageEntity> captor = ArgumentCaptor.forClass(OutboxMessageEntity.class);
        verify(repository).save(captor.capture());
        OutboxMessageEntity saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo("command-1");
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
