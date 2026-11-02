package com.sporty.settlement.repository.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessageEntity, String> {

    /**
     * Fetch retry-eligible or lease-expired messages in a stable order.
     *
     * PESSIMISTIC_WRITE serializes claim acquisition. Publication can overlap after lease
     * expiry, so consumers must tolerate redelivery.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from OutboxMessageEntity m
            where (m.status = :pending and m.nextAttemptAt <= :now)
               or (m.status = :processing and m.leaseUntil < :now)
            order by m.createdAt asc
            """)
    List<OutboxMessageEntity> findEligibleForClaim(
            @Param("now") Instant now,
            @Param("pending") OutboxStatus pending,
            @Param("processing") OutboxStatus processing,
            Pageable pageable);

    long countByStatus(OutboxStatus status);
}
