package com.sporty.settlement.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void scopedCorrelationIdIsRemovedWhenCallerHadNone() {
        CorrelationIdContext.run("scoped", () ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("scoped"));

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void scopedCorrelationIdRestoresCallerValueWhenActionFails() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "caller");
        var failure = new IllegalStateException("failed");

        assertThatThrownBy(() -> CorrelationIdContext.run("scoped", () -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("scoped");
            throw failure;
        })).isSameAs(failure);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("caller");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentCorrelationIdPreservesCallerContext(String correlationId) {
        MDC.put(CorrelationIdFilter.MDC_KEY, "caller");

        CorrelationIdContext.run(correlationId, () ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("caller"));

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("caller");
    }
}
