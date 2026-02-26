package com.sporty.settlement.service;

import com.sporty.settlement.service.IdempotencyService;
import com.sporty.settlement.repository.idempotency.ProcessedMessageEntity;
import com.sporty.settlement.repository.idempotency.ProcessedMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    @Test
    void executeOnce_runsSupplierOnlyOnce_perStageAndKey() {
        ProcessedMessageRepository repo = mock(ProcessedMessageRepository.class);

        // first call: ok; second call: simulate unique constraint violation
        when(repo.saveAndFlush(ArgumentMatchers.any(ProcessedMessageEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new DataIntegrityViolationException("dup"));

        IdempotencyService svc = new IdempotencyService(repo);

        AtomicInteger counter = new AtomicInteger();

        Integer first = svc.executeOnce("stage", "key", counter::incrementAndGet);
        Integer second = svc.executeOnce("stage", "key", counter::incrementAndGet);

        assertThat(first).isEqualTo(1);
        assertThat(second).isNull();
        assertThat(counter.get()).isEqualTo(1);
        verify(repo, times(2)).saveAndFlush(any());
    }

    @Test
    void executeOnce_rejectsBlankStageOrKey() {
        ProcessedMessageRepository repo = mock(ProcessedMessageRepository.class);
        IdempotencyService svc = new IdempotencyService(repo);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.executeOnce(" ", "k", () -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.executeOnce("s", " ", () -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");

        verifyNoInteractions(repo);
    }
}
