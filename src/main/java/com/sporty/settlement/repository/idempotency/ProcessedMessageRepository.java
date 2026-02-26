package com.sporty.settlement.repository.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessageEntity, Long> {
    boolean existsByStageAndMessageKey(String stage, String messageKey);
}
