package com.sporty.settlement.repository.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Minimal transactional outbox record.
 *
 * The goal is to make "produce" durable inside the same DB transaction as the business logic.
 * A separate dispatcher claims committed PENDING messages and marks successful
 * publication as SENT.
 */
@Entity
@Table(name = "outbox_messages", indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status,createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessageEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Version
    @Getter(AccessLevel.NONE)
    private Long version;

    @Column(nullable = false)
    private String destination; // logical dispatcher route, not a physical broker topic

    @Column(nullable = false)
    private String messageType; // e.g. "BetSettlementCommandV1"

    @Lob
    @Column(nullable = false)
    private String payloadJson;

    @Column(length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OutboxStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    private Instant nextAttemptAt;
    private Instant leaseUntil;

    @Column(length = 36)
    private String claimToken;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 2000)
    private String lastError;

    public OutboxMessageEntity(String id,
                              String destination,
                              String messageType,
                              String payloadJson,
                              String correlationId,
                              OutboxStatus status,
                              Instant createdAt) {
        this.id = id;
        this.destination = destination;
        this.messageType = messageType;
        this.payloadJson = payloadJson;
        this.correlationId = correlationId;
        this.status = status;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public void claim(String claimToken, Instant leaseUntil) {
        this.status = OutboxStatus.PROCESSING;
        this.claimToken = claimToken;
        this.leaseUntil = leaseUntil;
        this.attempts++;
        this.lastError = null;
    }

    public void markSent(Instant sentAt) {
        this.status = OutboxStatus.SENT;
        this.sentAt = sentAt;
        this.lastError = null;
        this.claimToken = null;
        this.leaseUntil = null;
    }

    public void releaseForRetry(String error, Instant nextAttemptAt) {
        this.status = OutboxStatus.PENDING;
        this.lastError = boundedError(error);
        this.nextAttemptAt = nextAttemptAt;
        this.claimToken = null;
        this.leaseUntil = null;
    }

    private static String boundedError(String error) {
        return error == null || error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    public void markFailedPermanently(String error) {
        this.status = OutboxStatus.FAILED;
        this.lastError = boundedError(error);
        this.claimToken = null;
        this.leaseUntil = null;
    }
}
