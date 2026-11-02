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

        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId)) {
            action.run();
        }
    }
}
