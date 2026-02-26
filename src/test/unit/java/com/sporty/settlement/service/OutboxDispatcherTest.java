package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import com.sporty.settlement.service.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {

    @Test
    void dispatch_whenNoNewMessages_doesNothing() {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(repo.findNextNew(any(PageRequest.class))).thenReturn(List.of());

        OutboxDispatcher dispatcher = new OutboxDispatcher(repo, publisher, mapper);
        dispatcher.dispatch();

        verifyNoInteractions(publisher);
    }

    @Test
    void dispatch_marksUnsupportedTypeAsFailed_withoutPublishing() {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        ObjectMapper mapper = mock(ObjectMapper.class);

        OutboxMessageEntity msg = new OutboxMessageEntity(
                "1", "bet-settlements", "SomeOtherType", "{}", null, OutboxStatus.NEW, Instant.now());

        when(repo.findNextNew(any(PageRequest.class))).thenReturn(List.of(msg));

        OutboxDispatcher dispatcher = new OutboxDispatcher(repo, publisher, mapper);
        dispatcher.dispatch();

        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(msg.getLastError()).contains("Unsupported");
        verifyNoInteractions(publisher);
    }

    @Test
    void dispatch_onSuccess_publishesAndMarksSent() throws Exception {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        ObjectMapper mapper = mock(ObjectMapper.class);

        OutboxMessageEntity msg = new OutboxMessageEntity(
                "1", "bet-settlements", BetSettlementCommandV1.class.getSimpleName(), "{...}", "corr-1", OutboxStatus.NEW, Instant.now());

        BetSettlementCommandV1 cmd = new BetSettlementCommandV1(
                "B1", "U1", "E1", "M1", "W1", new BigDecimal("10.00"), "W2", Instant.now(), "corr-1");
        when(repo.findNextNew(any(PageRequest.class))).thenReturn(List.of(msg));
        when(mapper.readValue(eq("{...}"), eq(BetSettlementCommandV1.class))).thenReturn(cmd);

        OutboxDispatcher dispatcher = new OutboxDispatcher(repo, publisher, mapper);
        dispatcher.dispatch();

        ArgumentCaptor<BetSettlementCommandV1> captor = ArgumentCaptor.forClass(BetSettlementCommandV1.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().betId()).isEqualTo("B1");

        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(msg.getSentAt()).isNotNull();
        assertThat(msg.getLastError()).isNull();
    }

    @Test
    void dispatch_onPublishFailure_incrementsAttempts_andEventuallyMarksFailed() throws Exception {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        ObjectMapper mapper = mock(ObjectMapper.class);

        OutboxMessageEntity msg = new OutboxMessageEntity(
                "1", "bet-settlements", BetSettlementCommandV1.class.getSimpleName(), "{...}", "corr-1", OutboxStatus.NEW, Instant.now());

        // Pre-seed attempts to 9, so the next failure hits MAX_ATTEMPTS(10)
        for (int i = 0; i < 9; i++) {
            msg.markAttemptFailed("seed");
        }
        assertThat(msg.getAttempts()).isEqualTo(9);

        BetSettlementCommandV1 cmd = new BetSettlementCommandV1(
                "B1", "U1", "E1", "M1", "W1", new BigDecimal("10.00"), "W2", Instant.now(), "corr-1");

        when(repo.findNextNew(any(PageRequest.class))).thenReturn(List.of(msg));
        when(mapper.readValue(eq("{...}"), eq(BetSettlementCommandV1.class))).thenReturn(cmd);
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        OutboxDispatcher dispatcher = new OutboxDispatcher(repo, publisher, mapper);
        dispatcher.dispatch();

        assertThat(msg.getAttempts()).isEqualTo(10);
        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(msg.getLastError()).contains("Max attempts reached");
    }

    @Test
    void dispatch_onInvalidJson_incrementsAttempts_butKeepsStatusNew_untilMaxAttempts() throws Exception {
        OutboxMessageRepository repo = mock(OutboxMessageRepository.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        ObjectMapper mapper = mock(ObjectMapper.class);

        OutboxMessageEntity msg = new OutboxMessageEntity(
                "1", "bet-settlements", BetSettlementCommandV1.class.getSimpleName(), "bad-json", "corr-1", OutboxStatus.NEW, Instant.now());
        when(repo.findNextNew(any(PageRequest.class))).thenReturn(List.of(msg));
        when(mapper.readValue(eq("bad-json"), eq(BetSettlementCommandV1.class)))
                .thenThrow(new RuntimeException("invalid json"));

        OutboxDispatcher dispatcher = new OutboxDispatcher(repo, publisher, mapper);
        dispatcher.dispatch();

        assertThat(msg.getAttempts()).isEqualTo(1);
        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(msg.getLastError()).contains("invalid json");
        verifyNoInteractions(publisher);
    }
}
