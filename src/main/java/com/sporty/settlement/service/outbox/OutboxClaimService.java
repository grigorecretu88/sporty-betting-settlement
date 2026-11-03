package com.sporty.settlement.service.outbox;

import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(1);
    private final OutboxMessageRepository repository;

    /**
     * Claims one row immediately before publication. Empty also means an exhausted lease
     * was made terminal; later eligible rows remain available to the next scheduled scan.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedOutboxMessage> claimNext() {
        Instant now = Instant.now();
        List<OutboxMessageEntity> eligible = repository.findEligibleForClaim(
                now, OutboxStatus.PENDING, OutboxStatus.PROCESSING, PageRequest.of(0, 1));
        if (eligible.isEmpty()) {
            return Optional.empty();
        }

        OutboxMessageEntity row = eligible.get(0);
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            row.markFailedPermanently("Claim budget exhausted after lease expiry; publication outcome unknown");
            return Optional.empty();
        }

        row.claim(UUID.randomUUID().toString(), now.plus(LEASE_DURATION));
        return Optional.of(snapshot(row));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(ClaimedOutboxMessage claimed) {
        ownedClaim(claimed).markSent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(ClaimedOutboxMessage claimed, String error, boolean terminal) {
        OutboxMessageEntity row = ownedClaim(claimed);
        if (terminal || row.getAttempts() >= MAX_ATTEMPTS) {
            row.markFailedPermanently(error);
        } else {
            row.releaseForRetry(error, Instant.now().plus(retryDelay(row.getAttempts())));
        }
    }

    private static Duration retryDelay(int attempt) {
        long exponentialSeconds = 1L << Math.min(attempt - 1, 6);
        return Duration.ofSeconds(Math.min(MAX_RETRY_DELAY.toSeconds(), exponentialSeconds));
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
