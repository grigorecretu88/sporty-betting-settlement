package com.sporty.settlement.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Ensures every HTTP request has a correlationId attached to logs and response.
 *
 * Header used: X-Correlation-Id
 * MDC key: correlationId
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ATTR = "correlationId";

    public static boolean isValid(String value) {
        return value != null && !value.isBlank() && value.length() <= 64
                && value.matches("[A-Za-z0-9._:-]+");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestedId = request.getHeader(HEADER);
        String correlationId = isValid(requestedId) ? requestedId : UUID.randomUUID().toString();

        request.setAttribute(REQUEST_ATTR, correlationId);
        response.setHeader(HEADER, correlationId);

        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, correlationId)) {
            filterChain.doFilter(request, response);
        }
    }
}
