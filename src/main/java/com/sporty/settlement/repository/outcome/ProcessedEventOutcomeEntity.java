package com.sporty.settlement.repository.outcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "processed_event_outcomes")
public class ProcessedEventOutcomeEntity {
    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Version
    private Long version;

    @Column(name = "event_name", nullable = false, length = 255)
    private String eventName;

    @Column(name = "winner_id", nullable = false, length = 64)
    private String winnerId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventOutcomeEntity() { }

    public ProcessedEventOutcomeEntity(String eventId, String eventName, String winnerId, Instant processedAt) {
        this.eventId = eventId;
        this.eventName = eventName;
        this.winnerId = winnerId;
        this.processedAt = processedAt;
    }

    public String getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public String getWinnerId() { return winnerId; }
    public Instant getProcessedAt() { return processedAt; }
}
