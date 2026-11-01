package com.sporty.settlement.repository.idempotency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ApiPublicationRepository extends JpaRepository<ApiPublicationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ApiPublicationEntity p where p.key = :key")
    Optional<ApiPublicationEntity> findLocked(String key);
}
