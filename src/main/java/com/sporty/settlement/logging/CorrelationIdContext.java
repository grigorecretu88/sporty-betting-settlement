package com.sporty.settlement.logging;

import org.slf4j.MDC;

public final class CorrelationIdContext {
    private CorrelationIdContext() {
    }

    public static void run(String correlationId, Runnable action) {
        if (correlationId == null || correlationId.isBlank()) {
            action.run();
            return;
        }

        String previousCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId)) {
            action.run();
        } finally {
            if (previousCorrelationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelationId);
            }
        }
    }
}
