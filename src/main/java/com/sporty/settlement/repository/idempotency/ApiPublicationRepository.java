package com.sporty.settlement.repository.idempotency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ApiPublicationRepository extends JpaRepository<ApiPublicationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ApiPublicationEntity> findLockedByKey(String key);
}
