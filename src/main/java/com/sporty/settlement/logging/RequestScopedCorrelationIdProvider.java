package com.sporty.settlement.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Component
public class RequestScopedCorrelationIdProvider implements CorrelationIdProvider {

    @Override
    public Optional<String> currentId() {
        // Preferred: explicit request attribute set by CorrelationIdFilter.
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            Object attr = request.getAttribute(CorrelationIdFilter.REQUEST_ATTR);
            if (attr instanceof String s && !s.isBlank()) {
                return Optional.of(s);
            }
        }

        // Fallback: MDC (best-effort). Useful for non-HTTP threads like Kafka listeners.
        return Optional.ofNullable(MDC.get(CorrelationIdFilter.MDC_KEY)).filter(s -> !s.isBlank());
    }
}
