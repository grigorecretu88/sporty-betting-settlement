package com.sporty.settlement.repository.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.time.Instant;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessageEntity, String> {

    /**
     * Fetch retry-eligible or lease-expired messages in a stable order.
     *
     * PESSIMISTIC_WRITE serializes claim acquisition. Publication can overlap after lease
     * expiry, so consumers must tolerate redelivery.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboxMessageEntity m where " +
            "(m.status = com.sporty.settlement.repository.outbox.OutboxStatus.PENDING and m.nextAttemptAt <= :now) " +
            "or (m.status = com.sporty.settlement.repository.outbox.OutboxStatus.PROCESSING and m.leaseUntil < :now) " +
            "order by m.createdAt asc")
    List<OutboxMessageEntity> findEligibleForClaim(Instant now, Pageable pageable);

    long countByStatus(OutboxStatus status);
}
