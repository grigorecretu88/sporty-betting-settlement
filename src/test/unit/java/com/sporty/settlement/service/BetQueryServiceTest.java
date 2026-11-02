package com.sporty.settlement.service;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.domain.Bet;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BetQueryServiceTest {

    @Test
    void listBetsMapsEntitiesToResponses() {
        BetRepository repo = mock(BetRepository.class);
        BetQueryService service = new BetQueryService(repo);

        Bet bet = new Bet("bet-1", "user-1", "event-1", "market-1", "winner-a", new BigDecimal("10"));
        when(repo.findAll()).thenReturn(List.of(bet));

        List<BetResponse> res = service.listBets();

        assertThat(res).hasSize(1);
        assertThat(res.get(0).betId()).isEqualTo("bet-1");
        assertThat(res.get(0).status()).isEqualTo("PENDING");
        verify(repo).findAll();
    }
}
