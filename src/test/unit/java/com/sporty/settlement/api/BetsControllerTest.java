package com.sporty.settlement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.api.dto.BetResponse;
import com.sporty.settlement.logging.ContextCorrelationIdProvider;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.service.BetQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BetsController.class)
@Import(ContextCorrelationIdProvider.class)
class BetsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockBean BetQueryService betQueryService;

    @Test
    void unexpectedFailureDoesNotExposeInternalDiagnostics() throws Exception {
        when(betQueryService.listBets()).thenThrow(new IllegalStateException("internal database details"));
        MvcResult result = mvc.perform(get("/api/bets"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String correlationId = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(mapper.readTree(responseBody).get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(responseBody).doesNotContain("internal database details");
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
