package com.sporty.settlement.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bets", indexes = {
        @Index(name = "idx_bets_event_id", columnList = "eventId")
})
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


    protected Bet() { }

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
        this.status = BetStatus.PENDING;
    }

    public String getBetId() { return betId; }
    public String getUserId() { return userId; }
    public String getEventId() { return eventId; }
    public String getEventMarketId() { return eventMarketId; }
    public String getEventWinnerId() { return eventWinnerId; }
    public BigDecimal getBetAmount() { return betAmount; }
    public BetStatus getStatus() { return status; }
    public Instant getSettledAt() { return settledAt; }
    public String getOutcomeWinnerId() { return outcomeWinnerId; }

    public void markSettled(boolean won, String outcomeWinnerId, Instant settledAt) {
        this.status = won ? BetStatus.SETTLED_WON : BetStatus.SETTLED_LOST;
        this.outcomeWinnerId = outcomeWinnerId;
        this.settledAt = settledAt;
    }
}

