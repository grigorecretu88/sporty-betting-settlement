package com.sporty.settlement.service;

import com.sporty.settlement.repository.idempotency.ProcessedMessageEntity;
import com.sporty.settlement.repository.idempotency.ProcessedMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Simple idempotency guard.
 *
 * Semantics: best-effort "exactly once" within a single DB.
 * We attempt to insert (stage, key) once. If already inserted -> treat as duplicate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedMessageRepository repository;

    /**
     * Runs the operation only once per (stage,key). Returns:
     * - the supplier result when first time
     * - null when duplicate
     */
    @Transactional
    public <T> T executeOnce(String stage, String key, Supplier<T> op) {
        if (stage == null || stage.isBlank()) throw new IllegalArgumentException("stage must not be blank");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");

        try {
            repository.saveAndFlush(new ProcessedMessageEntity(stage, key, Instant.now()));
        } catch (DataIntegrityViolationException dup) {
            log.info("Duplicate suppressed stage={} key={}", stage, key);
            return null;
        }
        return op.get();
    }
}
