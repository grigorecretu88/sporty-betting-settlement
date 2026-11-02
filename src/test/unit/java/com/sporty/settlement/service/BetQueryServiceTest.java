package com.sporty.settlement.service;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BetQueryServiceTest {

    @Test
    void listBetsMapsEntitiesToResponses() {
        BetRepository repo = mock(BetRepository.class);
        BetQueryService service = new BetQueryService(repo);

        Bet bet = new Bet("bet-1", "user-1", "event-1", "market-1", "winner-a",
                new BigDecimal("10.50"));
        Instant settledAt = Instant.parse("2026-02-26T09:00:00Z");
        bet.markSettled("winner-b", settledAt);
        when(repo.findAll()).thenReturn(List.of(bet));

        List<BetResponse> res = service.listBets();

        assertThat(res).containsExactly(new BetResponse(
                "bet-1", "user-1", "event-1", "market-1", "winner-a",
                new BigDecimal("10.50"), "SETTLED_LOST", settledAt, "winner-b"));
        verify(repo).findAll();
    }
}
