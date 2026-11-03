package com.sporty.settlement.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void filter_setsCorrelationIdInMdc_andResponseHeader() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/event-outcomes");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        String header = res.getHeader(CorrelationIdFilter.HEADER);
        assertThat(header).isNotBlank();
        // MDC is cleared at the end of the request
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void filter_preservesIncomingCorrelationId() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/event-outcomes");
        req.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
    }

    @Test
    void filterRestoresExistingCorrelationContext() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "caller");
        var request = new MockHttpServletRequest("GET", "/api/bets");
        var response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("caller");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "invalid value", "bad\r\nheader", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void invalidCorrelationIdIsReplacedAndMdcCleared(String invalid) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/bets");
        request.addHeader(CorrelationIdFilter.HEADER, invalid);
        var response = new MockHttpServletResponse();
        new CorrelationIdFilter().doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
            assertThat(CorrelationIdFilter.isValid(response.getHeader(CorrelationIdFilter.HEADER))).isTrue();
        });
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(invalid);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
