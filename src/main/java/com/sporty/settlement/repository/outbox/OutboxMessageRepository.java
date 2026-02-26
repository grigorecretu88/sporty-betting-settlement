package com.sporty.settlement.repository.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessageEntity, String> {

    /**
     * Fetch NEW messages in a stable order.
     *
     * PESSIMISTIC_WRITE prevents two dispatcher instances from publishing the same row concurrently
     * when running with a shared DB.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboxMessageEntity m where m.status = com.sporty.settlement.repository.outbox.OutboxStatus.NEW order by m.createdAt asc")
    List<OutboxMessageEntity> findNextNew(Pageable pageable);

    long countByStatus(OutboxStatus status);
}
