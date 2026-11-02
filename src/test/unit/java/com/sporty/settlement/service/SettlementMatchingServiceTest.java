package com.sporty.settlement.service;

import com.sporty.settlement.domain.*;
import com.sporty.settlement.repository.bet.BetRepository;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeEntity;
import com.sporty.settlement.repository.outcome.ProcessedEventOutcomeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        verify(outcomes).save(any(ProcessedEventOutcomeEntity.class));
        verify(outcomes).flush();
        verify(outbox, times(2)).enqueue(anyString(), eq("bet-settlements"),
                any(BetSettlementCommandV1.class), eq("corr-1"));
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
