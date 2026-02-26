package com.sporty.settlement.repository.outbox;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Minimal transactional outbox record.
 *
 * The goal is to make "produce" durable inside the same DB transaction as the business logic.
 * A separate dispatcher publishes NEW messages and marks them SENT.
 */
@Entity
@Table(name = "outbox_messages", indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status,createdAt")
})
public class OutboxMessageEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String destination; // e.g. "bet-settlements" topic

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

    @Column(nullable = false)
    private int attempts;

    @Column(length = 2000)
    private String lastError;

    protected OutboxMessageEntity() {
    }

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
        this.attempts = 0;
    }

    public String getId() { return id; }
    public String getDestination() { return destination; }
    public String getMessageType() { return messageType; }
    public String getPayloadJson() { return payloadJson; }
    public String getCorrelationId() { return correlationId; }
    public OutboxStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }

    public void markSent(Instant now) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    public void markAttemptFailed(String error) {
        this.attempts++;
        this.lastError = error;
    }

    public void markFailedPermanently(String error) {
        this.status = OutboxStatus.FAILED;
        this.lastError = error;
    }
}
