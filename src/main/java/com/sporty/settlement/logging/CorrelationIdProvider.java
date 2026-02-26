package com.sporty.settlement.logging;

import java.util.Optional;

/**
 * Provides the current correlation id in a way that is explicit at the edges
 * (HTTP request attribute) but can still fall back to MDC for non-HTTP threads
 * such as Kafka listeners / schedulers.
 */
public interface CorrelationIdProvider {

    Optional<String> currentId();
}
