package com.sporty.settlement.logging;

import com.sporty.settlement.logging.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

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
}
