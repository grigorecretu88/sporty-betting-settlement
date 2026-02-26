package com.sporty.settlement.repository.idempotency;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "processed_message",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_stage_key", columnNames = {"stage", "message_key"}))
public class ProcessedMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stage", nullable = false, length = 80)
    private String stage;

    @Column(name = "message_key", nullable = false, length = 200)
    private String messageKey;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessageEntity() {
        // for JPA
    }

    public ProcessedMessageEntity(String stage, String messageKey, Instant processedAt) {
        this.stage = stage;
        this.messageKey = messageKey;
        this.processedAt = processedAt;
    }

    public Long getId() { return id; }
    public String getStage() { return stage; }
    public String getMessageKey() { return messageKey; }
    public Instant getProcessedAt() { return processedAt; }
}
