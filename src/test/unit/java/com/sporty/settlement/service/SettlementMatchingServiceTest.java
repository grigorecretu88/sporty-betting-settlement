package com.sporty.settlement.service;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeEntity;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeRepository;
import com.sporty.settlement.service.outbox.OutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SettlementMatchingServiceTest {
    @Test
    void newOutcomeStoresIdentityAndOneIntentPerPendingMatchingBet() {
        BetRepository bets = mock(BetRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        ProcessedEventOutcomeRepository outcomes = mock(ProcessedEventOutcomeRepository.class);
        when(outcomes.findById("event-100")).thenReturn(Optional.empty());
        when(bets.findByEventIdAndStatus("event-100", BetStatus.PENDING)).thenReturn(List.of(
                new Bet("bet-1", "user-1", "event-100", "market-1", "team-a", new BigDecimal("10")),
                new Bet("bet-2", "user-2", "event-100", "market-1", "team-b", new BigDecimal("25"))));

        var service = new SettlementMatchingService(bets, outbox, outcomes);
        var result = service.process(new EventOutcomeV1("event-100", "Derby", "team-a"), "corr-1");

        assertThat(result).isEqualTo(OutcomeProcessingResult.accepted(2));
        ArgumentCaptor<ProcessedEventOutcomeEntity> outcomeCaptor =
                ArgumentCaptor.forClass(ProcessedEventOutcomeEntity.class);
        verify(outcomes).save(outcomeCaptor.capture());
        assertThat(outcomeCaptor.getValue()).satisfies(saved -> {
            assertThat(saved.getEventId()).isEqualTo("event-100");
            assertThat(saved.getEventName()).isEqualTo("Derby");
            assertThat(saved.getWinnerId()).isEqualTo("team-a");
            assertThat(saved.getProcessedAt()).isNotNull();
        });
        verify(outcomes).flush();

        ArgumentCaptor<BetSettlementCommandV1> commandCaptor =
                ArgumentCaptor.forClass(BetSettlementCommandV1.class);
        verify(outbox, times(2)).enqueue(commandCaptor.capture());
        List<BetSettlementCommandV1> commands = commandCaptor.getAllValues();
        assertThat(commands).extracting(
                        BetSettlementCommandV1::commandId,
                        BetSettlementCommandV1::betId,
                        BetSettlementCommandV1::userId,
                        BetSettlementCommandV1::eventId,
                        BetSettlementCommandV1::eventMarketId,
                        BetSettlementCommandV1::eventWinnerId,
                        BetSettlementCommandV1::outcomeWinnerId,
                        BetSettlementCommandV1::betAmount,
                        BetSettlementCommandV1::correlationId)
                .containsExactly(
                        tuple("ef4b6e78-2520-3c6a-b9fc-cdbaad807cbd", "bet-1", "user-1",
                                "event-100", "market-1", "team-a", "team-a",
                                new BigDecimal("10"), "corr-1"),
                        tuple("44287932-4bb0-30a3-bdff-894b9afbdd62", "bet-2", "user-2",
                                "event-100", "market-1", "team-b", "team-a",
                                new BigDecimal("25"), "corr-1"));
        assertThat(commands).extracting(BetSettlementCommandV1::occurredAt).doesNotContainNull();
        assertThat(commands).extracting(BetSettlementCommandV1::commandId).doesNotHaveDuplicates();
        verify(bets).findByEventIdAndStatus("event-100", BetStatus.PENDING);
        verifyNoMoreInteractions(bets, outbox);
    }

    @Test
    void commandIdentityIsStableForTheSameLogicalInputsAndDistinctAcrossBets() {
        String first = SettlementMatchingService.deterministicCommandId("event-100", "team-a", "bet-1");

        assertThat(first)
                .isEqualTo("ef4b6e78-2520-3c6a-b9fc-cdbaad807cbd")
                .isEqualTo(SettlementMatchingService.deterministicCommandId("event-100", "team-a", "bet-1"))
                .isNotEqualTo(SettlementMatchingService.deterministicCommandId("event-100", "team-a", "bet-2"));
    }

    @Test
    void sameWinnerWithChangedNameIsDuplicateAndConflictingWinnerIsRejected() {
        BetRepository bets = mock(BetRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        ProcessedEventOutcomeRepository outcomes = mock(ProcessedEventOutcomeRepository.class);
        when(outcomes.findById("event-100")).thenReturn(Optional.of(
                new ProcessedEventOutcomeEntity("event-100", "Derby", "team-a", Instant.now())));
        var service = new SettlementMatchingService(bets, outbox, outcomes);

        assertThat(service.process(new EventOutcomeV1("event-100", "Renamed Derby", "team-a"), "c").status())
                .isEqualTo(OutcomeProcessingResult.Status.DUPLICATE);
        assertThat(service.process(new EventOutcomeV1("event-100", "Derby", "team-b"), "c").status())
                .isEqualTo(OutcomeProcessingResult.Status.CONFLICT);
        verifyNoInteractions(outbox, bets);
    }
}
