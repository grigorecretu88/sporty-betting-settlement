package com.sporty.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bets", indexes = {
        @Index(name = "idx_bets_event_id", columnList = "eventId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bet {

    @Id
    private String betId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private String eventMarketId;

    @Column(nullable = false)
    private String eventWinnerId; // the selection / predicted winner

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal betAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BetStatus status = BetStatus.PENDING;

    @Column
    private Instant settledAt;

    @Column
    private String outcomeWinnerId; // actual winner at settlement time

    @Version
    @Getter(AccessLevel.NONE)
    private Long version;

    public Bet(String betId,
               String userId,
               String eventId,
               String eventMarketId,
               String eventWinnerId,
               BigDecimal betAmount) {
        this.betId = betId;
        this.userId = userId;
        this.eventId = eventId;
        this.eventMarketId = eventMarketId;
        this.eventWinnerId = eventWinnerId;
        this.betAmount = betAmount;
    }

    public void markSettled(String outcomeWinnerId, Instant settledAt) {
        if (status != BetStatus.PENDING) {
            if (this.outcomeWinnerId != null && this.outcomeWinnerId.equals(outcomeWinnerId)) {
                return;
            }
            throw new IllegalStateException("Bet " + betId + " is already settled with a different outcome");
        }
        if (outcomeWinnerId == null || outcomeWinnerId.isBlank()) {
            throw new IllegalArgumentException("outcomeWinnerId must not be blank");
        }
        if (settledAt == null) {
            throw new IllegalArgumentException("settledAt must not be null");
        }
        this.status = eventWinnerId.equals(outcomeWinnerId) ? BetStatus.SETTLED_WON : BetStatus.SETTLED_LOST;
        this.outcomeWinnerId = outcomeWinnerId;
        this.settledAt = settledAt;
    }
}

