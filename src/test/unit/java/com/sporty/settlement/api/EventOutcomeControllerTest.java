package com.sporty.settlement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.exception.IdempotencyKeyConflictException;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.logging.ContextCorrelationIdProvider;
import com.sporty.settlement.service.PublishEventOutcomeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventOutcomeController.class)
@Import(ContextCorrelationIdProvider.class)
class EventOutcomeControllerTest {

    private static final String ENDPOINT = "/api/event-outcomes";
    private static final String CORRELATION_HEADER = CorrelationIdFilter.HEADER;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @MockBean
    PublishEventOutcomeService publishService;

    @Test
    void unsupportedMethodRetainsMethodNotAllowedAndAllowHeader() throws Exception {
        mvc.perform(get(ENDPOINT))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        verifyNoInteractions(publishService);
    }

    @Test
    void unsupportedContentTypeIsAClientError() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.TEXT_PLAIN).content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
        verifyNoInteractions(publishService);
    }

    @Test
    void invalidBodyReturnsBadRequestWithoutCallingService() throws Exception {
        String body = """
                {"eventId":"","eventName":"","eventWinnerId":""}
                """;

        MvcResult result = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        assertCorrelationHeaderMatchesBody(result);
        verify(publishService, never()).publish(any(), any(), any());
    }

    @Test
    void invalidJsonReturnsStableBadRequestWithoutCallingService() throws Exception {
        String body = "{not-valid-json";

        MvcResult result = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(jsonPath("$.code").value("INVALID_JSON"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        assertCorrelationHeaderMatchesBody(result);
        verify(publishService, never()).publish(any(), any(), any());
    }

    @Test
    void validOutcomeReturnsAcceptedAndCallsServiceWithCorrelationId() throws Exception {
        PublishEventOutcomeRequest request = new PublishEventOutcomeRequest("event-123", "Derby", "team-a");

        MvcResult result = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(content().string(""))
                .andReturn();

        String correlationId = result.getResponse().getHeader(CORRELATION_HEADER);
        assertThat(correlationId).isNotBlank();
        verify(publishService).publish(request, null, correlationId);
    }

    @Test
    void idempotencyKeyIsPassedToService() throws Exception {
        PublishEventOutcomeRequest request = new PublishEventOutcomeRequest("event-123", "Derby", "team-a");

        mvc.perform(post(ENDPOINT)
                        .header(EventOutcomeController.IDEMPOTENCY_KEY_HEADER, "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isAccepted());

        verify(publishService).publish(any(PublishEventOutcomeRequest.class), eq("abc-123"), anyString());
    }

    @Test
    void publicationFailureMapsToStableError() throws Exception {
        doThrow(new EventOutcomePublishException("boom", new RuntimeException("kafka down")))
                .when(publishService).publish(any(), any(), any());

        PublishEventOutcomeRequest request = new PublishEventOutcomeRequest("event-123", "Derby", "team-a");

        MvcResult result = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists(CORRELATION_HEADER))
                .andExpect(jsonPath("$.code").value("EVENT_OUTCOME_PUBLISH_FAILED"))
                .andExpect(jsonPath("$.message").value("Event outcome publication could not be confirmed"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        assertCorrelationHeaderMatchesBody(result);
    }

    @Test
    void conflictingIdempotencyKeyReturnsClientError() throws Exception {
        doThrow(new IdempotencyKeyConflictException(
                "Idempotency-Key was already used for a different payload"))
                .when(publishService).publish(any(), any(), any());

        MvcResult result = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PublishEventOutcomeRequest("event-123", "Derby", "team-a"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key was already used for a different payload"))
                .andReturn();

        assertCorrelationHeaderMatchesBody(result);
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private void assertCorrelationHeaderMatchesBody(MvcResult result) throws Exception {
        String correlationId = result.getResponse().getHeader(CORRELATION_HEADER);
        assertThat(correlationId).isNotBlank();
        String body = result.getResponse().getContentAsString();
        assertThat(body).isNotBlank();

        assertThat(mapper.readTree(body).get("correlationId").asText()).isEqualTo(correlationId);
    }
}
