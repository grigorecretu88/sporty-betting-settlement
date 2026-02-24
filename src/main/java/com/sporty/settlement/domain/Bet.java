package com.sporty.settlement.domain;

import jakarta.persistence.*;

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

    @Column(nullable = false)
    private double betAmount;

    protected Bet() { }

    public Bet(String betId,
               String userId,
               String eventId,
               String eventMarketId,
               String eventWinnerId,
               double betAmount) {
        this.betId = betId;
        this.userId = userId;
        this.eventId = eventId;
        this.eventMarketId = eventMarketId;
        this.eventWinnerId = eventWinnerId;
        this.betAmount = betAmount;
    }

    public String getBetId() { return betId; }
    public String getUserId() { return userId; }
    public String getEventId() { return eventId; }
    public String getEventMarketId() { return eventMarketId; }
    public String getEventWinnerId() { return eventWinnerId; }
    public double getBetAmount() { return betAmount; }
}
