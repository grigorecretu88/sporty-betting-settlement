package com.sporty.settlement.service;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.domain.BetView;
import com.sporty.settlement.repository.bet.BetRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BetQueryServiceTest {

    @Test
    void listBets_mapsProjectionToDto() {
        BetRepository repo = mock(BetRepository.class);
        BetQueryService service = new BetQueryService(repo);

        BetView v = mock(BetView.class);
        when(v.getBetId()).thenReturn("bet-1");
        when(v.getUserId()).thenReturn("user-1");
        when(v.getEventId()).thenReturn("event-1");
        when(v.getEventMarketId()).thenReturn("market-1");
        when(v.getEventWinnerId()).thenReturn("winner-a");
        when(v.getBetAmount()).thenReturn(new BigDecimal("10"));
        when(v.getStatus()).thenReturn("PENDING");
        when(v.getSettledAt()).thenReturn(Instant.parse("2026-02-26T09:00:00Z"));
        when(v.getOutcomeWinnerId()).thenReturn("winner-a");

        when(repo.findAllBy()).thenReturn(List.of(v));

        List<BetResponse> res = service.listBets();

        assertThat(res).hasSize(1);
        assertThat(res.get(0).betId()).isEqualTo("bet-1");
        assertThat(res.get(0).status()).isEqualTo("PENDING");
        verify(repo).findAllBy();
    }
}