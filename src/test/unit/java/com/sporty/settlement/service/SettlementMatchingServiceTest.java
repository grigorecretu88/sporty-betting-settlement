package com.sporty.settlement.service;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SettlementMatchingServiceTest {

    @Test
    void matchAndEnqueue_whenNoBets_doesNotEnqueueAnything() {
        BetRepository betRepository = mock(BetRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        when(betRepository.findByEventIdAndStatus("E1", BetStatus.PENDING)).thenReturn(List.of());

        SettlementMatchingService svc = new SettlementMatchingService(betRepository, outbox);
        int matched = svc.matchAndEnqueue(new EventOutcomeV1("E1", "Event", "W1"), "corr-1");

        assertThat(matched).isZero();
        verify(outbox, never()).enqueue(anyString(), any(), any());
    }

    @Test
    void matchAndEnqueue_whenBetsExist_enqueuesOneMessagePerBetWithCorrectFields() {
        BetRepository betRepository = mock(BetRepository.class);
        OutboxService outbox = mock(OutboxService.class);

        Bet bet1 = new Bet("B1", "U1", "E1", "M1", "W1", new BigDecimal("10.00"));
        Bet bet2 = new Bet("B2", "U2", "E1", "M2", "W2", new BigDecimal("25.50"));
        when(betRepository.findByEventIdAndStatus("E1", BetStatus.PENDING)).thenReturn(List.of(bet1, bet2));

        SettlementMatchingService svc = new SettlementMatchingService(betRepository, outbox);
        int matched = svc.matchAndEnqueue(new EventOutcomeV1("E1", "Event", "W1"), "corr-1");

        assertThat(matched).isEqualTo(2);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(2)).enqueue(eq("bet-settlements"), payloadCaptor.capture(), eq("corr-1"));

        List<Object> payloads = payloadCaptor.getAllValues();
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).isInstanceOf(BetSettlementCommandV1.class);
        assertThat(payloads.get(1)).isInstanceOf(BetSettlementCommandV1.class);

        BetSettlementCommandV1 p1 = (BetSettlementCommandV1) payloads.get(0);
        assertThat(p1.betId()).isEqualTo("B1");
        assertThat(p1.userId()).isEqualTo("U1");
        assertThat(p1.eventId()).isEqualTo("E1");
        assertThat(p1.eventMarketId()).isEqualTo("M1");
        assertThat(p1.eventWinnerId()).isEqualTo("W1");
        assertThat(p1.outcomeWinnerId()).isEqualTo("W1");
        assertThat(p1.betAmount()).isEqualByComparingTo("10.00");
        assertThat(p1.occurredAt()).isNotNull();
        assertThat(p1.correlationId()).isEqualTo("corr-1");
        assertThat(p1.occurredAt()).isBeforeOrEqualTo(Instant.now());

        BetSettlementCommandV1 p2 = (BetSettlementCommandV1) payloads.get(1);
        assertThat(p2.betId()).isEqualTo("B2");
        assertThat(p2.userId()).isEqualTo("U2");
        assertThat(p2.eventMarketId()).isEqualTo("M2");
        assertThat(p2.eventWinnerId()).isEqualTo("W2");
        assertThat(p2.outcomeWinnerId()).isEqualTo("W1");
        assertThat(p2.betAmount()).isEqualByComparingTo("25.50");
        assertThat(p2.occurredAt()).isNotNull();
        assertThat(p2.correlationId()).isEqualTo("corr-1");
    }
}
