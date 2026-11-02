package com.sporty.settlement.service;

import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxClaimService {
    // Claim only what this single dispatcher can publish within one lease.
    private static final int BATCH_SIZE = 1;
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private final OutboxMessageRepository repository;

    public OutboxClaimService(OutboxMessageRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedOutboxMessage> claimBatch() {
        Instant now = Instant.now();
        List<OutboxMessageEntity> eligible = repository.findEligibleForClaim(now, PageRequest.of(0, BATCH_SIZE));
        if (eligible.isEmpty()) {
            return List.of();
        }

        OutboxMessageEntity row = eligible.get(0);
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            row.markFailedPermanently("Claim budget exhausted after lease expiry; publication outcome unknown");
            return List.of();
        }

        row.claim(UUID.randomUUID().toString(), now.plus(LEASE_DURATION));
        return List.of(snapshot(row));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(ClaimedOutboxMessage claimed) {
        ownedClaim(claimed).markSent(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(ClaimedOutboxMessage claimed, String error, boolean terminal) {
        OutboxMessageEntity row = ownedClaim(claimed);
        if (terminal || row.getAttempts() >= MAX_ATTEMPTS) {
            row.markFailedPermanently(error);
        } else {
            long seconds = Math.min(60, 1L << Math.min(row.getAttempts() - 1, 6));
            row.releaseForRetry(error, Instant.now().plusSeconds(seconds));
        }
    }

    private OutboxMessageEntity ownedClaim(ClaimedOutboxMessage claimed) {
        OutboxMessageEntity row = repository.findById(claimed.id())
                .orElseThrow(() -> new IllegalStateException("Outbox row disappeared: " + claimed.id()));
        if (row.getStatus() != OutboxStatus.PROCESSING || !claimed.claimToken().equals(row.getClaimToken())
                || row.getLeaseUntil() == null || !row.getLeaseUntil().isAfter(Instant.now())) {
            throw new IllegalStateException("Outbox claim is no longer owned: " + claimed.id());
        }
        return row;
    }

    private static ClaimedOutboxMessage snapshot(OutboxMessageEntity row) {
        return new ClaimedOutboxMessage(row.getId(), row.getClaimToken(), row.getDestination(),
                row.getMessageType(), row.getPayloadJson(), row.getCorrelationId(), row.getAttempts());
    }
}
