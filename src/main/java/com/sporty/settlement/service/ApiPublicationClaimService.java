package com.sporty.settlement.service;

import com.sporty.settlement.exception.IdempotencyKeyConflictException;
import com.sporty.settlement.repository.idempotency.ApiPublicationEntity;
import com.sporty.settlement.repository.idempotency.ApiPublicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiPublicationClaimService {
    public enum Status { CLAIMED, ALREADY_PUBLISHED, IN_PROGRESS }
    public record Claim(Status status, String key, String token) { }

    private final ApiPublicationRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String key, String payloadHash) {
        Instant now = Instant.now();
        var existing = repository.findLocked(key);
        if (existing.isPresent()) {
            ApiPublicationEntity row = existing.get();
            if (!row.getPayloadHash().equals(payloadHash)) {
                throw new IdempotencyKeyConflictException(
                        "Idempotency-Key was already used for a different payload");
            }
            if (row.getStatus() == ApiPublicationEntity.Status.PUBLISHED) {
                return new Claim(Status.ALREADY_PUBLISHED, key, null);
            }
            if (row.getLeaseUntil().isAfter(now)) {
                return new Claim(Status.IN_PROGRESS, key, null);
            }
            String token = UUID.randomUUID().toString();
            row.reclaim(token, now.plus(30, ChronoUnit.SECONDS));
            return new Claim(Status.CLAIMED, key, token);
        }
        String token = UUID.randomUUID().toString();
        repository.saveAndFlush(new ApiPublicationEntity(key, payloadHash, token,
                now.plus(30, ChronoUnit.SECONDS)));
        return new Claim(Status.CLAIMED, key, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Claim claim) {
        ApiPublicationEntity row = owned(claim);
        row.published(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Claim claim) {
        repository.delete(owned(claim));
    }

    private ApiPublicationEntity owned(Claim claim) {
        ApiPublicationEntity row = repository.findLocked(claim.key())
                .orElseThrow(() -> new IllegalStateException("Publication claim disappeared"));
        if (claim.token() == null || !claim.token().equals(row.getClaimToken())
                || !row.getLeaseUntil().isAfter(Instant.now())) {
            throw new IllegalStateException("Publication claim is no longer owned");
        }
        return row;
    }
}
