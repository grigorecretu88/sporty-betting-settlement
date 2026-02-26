package com.sporty.settlement.service;

import com.sporty.settlement.repository.idempotency.ProcessedMessageEntity;
import com.sporty.settlement.repository.idempotency.ProcessedMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    @Test
    void executeOnce_runsSupplierOnlyOnce_perStageAndKey() {
        ProcessedMessageRepository repo = mock(ProcessedMessageRepository.class);

        // first call -> not exists; second call -> exists
        when(repo.existsByStageAndMessageKey("stage", "key"))
                .thenReturn(false)
                .thenReturn(true);

        when(repo.save(any(ProcessedMessageEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        IdempotencyService svc = new IdempotencyService(repo);

        AtomicInteger counter = new AtomicInteger();

        Integer first = svc.executeOnce("stage", "key", counter::incrementAndGet);
        Integer second = svc.executeOnce("stage", "key", counter::incrementAndGet);

        assertThat(first).isEqualTo(1);
        assertThat(second).isNull();
        assertThat(counter.get()).isEqualTo(1);

        verify(repo, times(2)).existsByStageAndMessageKey("stage", "key");
        verify(repo, times(1)).save(any(ProcessedMessageEntity.class));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void executeOnce_rejectsBlankStageOrKey() {
        ProcessedMessageRepository repo = mock(ProcessedMessageRepository.class);
        IdempotencyService svc = new IdempotencyService(repo);

        assertThatThrownBy(() -> svc.executeOnce(" ", "k", () -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage");

        assertThatThrownBy(() -> svc.executeOnce("s", " ", () -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");

        verifyNoInteractions(repo);
    }
}