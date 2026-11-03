package com.sporty.settlement.service;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BetSettlementServiceTest {
    @Test
    void settlesOnceAndTreatsSameOutcomeAsDuplicate() {
        Bet bet = new Bet("bet-1", "user-1", "event-100", "market-1", "team-a", new BigDecimal("10"));
        BetRepository repository = mock(BetRepository.class);
        when(repository.findLockedByBetId("bet-1")).thenReturn(Optional.of(bet));
        var service = new BetSettlementService(repository);

        assertThat(service.settle(command("team-a"))).isEqualTo(BetSettlementService.Result.SETTLED_WON);
        assertThat(service.settle(command("team-a"))).isEqualTo(BetSettlementService.Result.DUPLICATE);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.SETTLED_WON);
    }

    @Test
    void conflictingRedeliveryCannotOverwriteFinalState() {
        Bet bet = new Bet("bet-1", "user-1", "event-100", "market-1", "team-a", new BigDecimal("10"));
        bet.markSettled("team-a", Instant.now());
        BetRepository repository = mock(BetRepository.class);
        when(repository.findLockedByBetId("bet-1")).thenReturn(Optional.of(bet));

        assertThatThrownBy(() -> new BetSettlementService(repository).settle(command("team-b")))
                .isInstanceOf(BetSettlementException.class).hasMessageContaining("Conflicting");
        assertThat(bet.getStatus()).isEqualTo(BetStatus.SETTLED_WON);
    }

    private static BetSettlementCommandV1 command(String outcomeWinner) {
        return new BetSettlementCommandV1("cmd", "bet-1", "user-1", "event-100", "market-1",
                "team-a", new BigDecimal("10"), outcomeWinner, Instant.now(), "corr");
    }
}
