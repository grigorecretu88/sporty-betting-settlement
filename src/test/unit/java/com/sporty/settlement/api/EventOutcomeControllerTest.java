package com.sporty.settlement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.logging.RequestScopedCorrelationIdProvider;
import com.sporty.settlement.service.PublishEventOutcomeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = EventOutcomeController.class)
@Import({ApiExceptionHandler.class, CorrelationIdFilter.class, RequestScopedCorrelationIdProvider.class})
class EventOutcomeControllerTest {

    private static final String ENDPOINT = "/api/event-outcomes";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @MockBean
    PublishEventOutcomeService publishService;

    @Test
    void post_withInvalidBody_returns400_andDoesNotCallService() throws Exception {
        // Blank required fields -> Bean Validation should reject
        String body = """
                {"eventId":"","eventName":"","eventWinnerId":""}
                """;

        MvcResult res = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        assertCorrelationHeaderMatchesBody(res);
        verify(publishService, never()).publish(any(), any(), any());
    }

    @Test
    void post_withInvalidJson_returns400_withStableError_andDoesNotCallService() throws Exception {
        String body = "{not-valid-json";

        MvcResult res = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(jsonPath("$.code").value("INVALID_JSON"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        assertCorrelationHeaderMatchesBody(res);
        verify(publishService, never()).publish(any(), any(), any());
    }

    @Test
    void post_happyPath_returns202_noBody_andCallsServiceWithCorrelationId() throws Exception {
        PublishEventOutcomeRequest req = new PublishEventOutcomeRequest("event-123", "Derby", "team-a");

        MvcResult res = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(content().string("")) // 202 Accepted with empty body is the intended contract
                .andReturn();

        // Capture arguments for stronger assertions than any()
        ArgumentCaptor<PublishEventOutcomeRequest> reqCaptor = ArgumentCaptor.forClass(PublishEventOutcomeRequest.class);
        ArgumentCaptor<String> idempotencyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);

        verify(publishService).publish(reqCaptor.capture(), idempotencyCaptor.capture(), correlationCaptor.capture());

        assertThat(reqCaptor.getValue().eventId()).isEqualTo("event-123");
        assertThat(reqCaptor.getValue().eventName()).isEqualTo("Derby");
        assertThat(reqCaptor.getValue().eventWinnerId()).isEqualTo("team-a");

        assertThat(idempotencyCaptor.getValue()).isNull();
        assertThat(correlationCaptor.getValue()).isNotBlank();

        // Ensure the correlation id passed to service equals the response header value
        String headerCorr = res.getResponse().getHeader(CORRELATION_HEADER);
        assertThat(headerCorr).isNotBlank();
        assertThat(correlationCaptor.getValue()).isEqualTo(headerCorr);
    }

    @Test
    void post_withIdempotencyKeyHeader_passesItToService() throws Exception {
        PublishEventOutcomeRequest req = new PublishEventOutcomeRequest("event-123", "Derby", "team-a");

        mvc.perform(post(ENDPOINT)
                        .header(EventOutcomeController.IDEMPOTENCY_KEY_HEADER, "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isAccepted());

        verify(publishService).publish(any(PublishEventOutcomeRequest.class), eq("abc-123"), anyString());
    }

    @Test
    void post_whenServiceThrows_mapsToStableErrorJson() throws Exception {
        doThrow(new EventOutcomePublishException("boom", new RuntimeException("kafka down")))
                .when(publishService).publish(any(), any(), any());

        PublishEventOutcomeRequest req = new PublishEventOutcomeRequest("event-123", "Derby", "team-a");

        MvcResult res = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(jsonPath("$.code").value("EVENT_OUTCOME_PUBLISH_FAILED"))
                .andExpect(jsonPath("$.message").value("boom"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        assertCorrelationHeaderMatchesBody(res);
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private void assertCorrelationHeaderMatchesBody(MvcResult res) throws Exception {
        String headerCorr = res.getResponse().getHeader(CORRELATION_HEADER);
        assertThat(headerCorr).isNotBlank();

        // Extract correlationId field from JSON response body and compare
        String body = res.getResponse().getContentAsString();
        assertThat(body).isNotBlank();

        String bodyCorr = mapper.readTree(body).get("correlationId").asText();
        assertThat(bodyCorr).isEqualTo(headerCorr);
    }
}