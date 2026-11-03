package com.sporty.settlement.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BetTest {
    @Test
    void actualWinnerDeterminesStateAndDuplicatePreservesOriginalTimestamp() {
        Bet winner = bet();
        winner.markSettled("a", Instant.EPOCH);
        winner.markSettled("a", Instant.EPOCH.plusSeconds(1));
        assertThat(winner.getStatus()).isEqualTo(BetStatus.SETTLED_WON);
        assertThat(winner.getSettledAt()).isEqualTo(Instant.EPOCH);
        Bet loser = bet();
        loser.markSettled("b", Instant.EPOCH);
        assertThat(loser.getStatus()).isEqualTo(BetStatus.SETTLED_LOST);
        assertThat(loser.getOutcomeWinnerId()).isEqualTo("b");
    }

    @Test
    void conflictingOutcomeCannotOverwriteFinalStateWithoutServiceProtection() {
        Bet bet = bet();
        bet.markSettled("a", Instant.EPOCH);
        assertThatThrownBy(() -> bet.markSettled("b", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.SETTLED_WON);
        assertThat(bet.getOutcomeWinnerId()).isEqualTo("a");
        assertThat(bet.getSettledAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void invalidOutcomeCannotPartiallyMutatePendingBet() {
        Bet bet = bet();
        assertThatThrownBy(() -> bet.markSettled(" ", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bet.markSettled("a", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.PENDING);
        assertThat(bet.getOutcomeWinnerId()).isNull();
        assertThat(bet.getSettledAt()).isNull();
    }

    private static Bet bet() {
        return new Bet("bet", "user", "event", "market", "a", BigDecimal.TEN);
    }
}
