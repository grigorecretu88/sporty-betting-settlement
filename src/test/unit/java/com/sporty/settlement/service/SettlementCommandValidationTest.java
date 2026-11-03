package com.sporty.settlement.service;

import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.domain.BetStatus;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementCommandValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"bet", "user", "event", "market", "selection", "amount"})
    void mismatchedCommandCannotMutateBet(String field) {
        Bet bet = new Bet("b", "u", "e", "m", "w", BigDecimal.TEN);
        BetRepository repository = mock(BetRepository.class);
        String id = field.equals("bet") ? "missing" : "b";
        when(repository.findLockedByBetId(id))
                .thenReturn(field.equals("bet") ? Optional.empty() : Optional.of(bet));
        var command = new BetSettlementCommandV1("c", id,
                field.equals("user") ? "wrong" : "u", field.equals("event") ? "wrong" : "e",
                field.equals("market") ? "wrong" : "m", field.equals("selection") ? "wrong" : "w",
                field.equals("amount") ? BigDecimal.ONE : BigDecimal.TEN, "w", Instant.now(), null);
        assertThatThrownBy(() -> new BetSettlementService(repository).settle(command))
                .isInstanceOf(BetSettlementException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.PENDING);
        assertThat(bet.getOutcomeWinnerId()).isNull();
    }
}
