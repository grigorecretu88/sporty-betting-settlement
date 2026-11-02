package com.sporty.settlement.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Component
public class ContextCorrelationIdProvider implements CorrelationIdProvider {

    @Override
    public Optional<String> currentId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTR);
            if (correlationId instanceof String value && !value.isBlank()) {
                return Optional.of(value);
            }
        }

        return Optional.ofNullable(MDC.get(CorrelationIdFilter.MDC_KEY))
                .filter(value -> !value.isBlank());
    }
}
