package com.sporty.settlement.api;

import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.logging.CorrelationIdProvider;
import com.sporty.settlement.service.BetQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BetsController.class)
class BetsControllerTest {

    @Autowired MockMvc mvc;

    @MockBean BetQueryService betQueryService;
    @MockBean CorrelationIdProvider correlationIdProvider;

    @Test
    void unexpectedFailureDoesNotExposeInternalDiagnostics() throws Exception {
        when(betQueryService.listBets()).thenThrow(new IllegalStateException("internal database details"));
        mvc.perform(get("/api/bets"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void listReturnsBets() throws Exception {
        when(betQueryService.listBets()).thenReturn(List.of(
                new BetResponse(
                        "bet-1", "user-1", "event-1", "market-1", "winner-a",
                        new BigDecimal("10"), "PENDING", null, null
                )
        ));

        mvc.perform(get("/api/bets"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].betId").value("bet-1"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void listReturnsEmptyArrayWhenNoBetsExist() throws Exception {
        when(betQueryService.listBets()).thenReturn(List.of());

        mvc.perform(get("/api/bets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
