package com.sporty.settlement.repository.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "api_publications")
public class ApiPublicationEntity {
    public enum Status { IN_PROGRESS, PUBLISHED }

    @Id
    @Column(name = "idempotency_key", length = 200)
    private String key;
    @Version
    private Long version;
    @Column(nullable = false, length = 64)
    private String payloadHash;
    @Column(nullable = false, length = 36)
    private String claimToken;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false)
    private Instant leaseUntil;
    private Instant publishedAt;

    protected ApiPublicationEntity() { }

    public ApiPublicationEntity(String key, String payloadHash, String claimToken, Instant leaseUntil) {
        this.key = key;
        this.payloadHash = payloadHash;
        this.claimToken = claimToken;
        this.status = Status.IN_PROGRESS;
        this.leaseUntil = leaseUntil;
    }

    public String getPayloadHash() { return payloadHash; }
    public String getClaimToken() { return claimToken; }
    public Status getStatus() { return status; }
    public Instant getLeaseUntil() { return leaseUntil; }

    public void reclaim(String token, Instant until) {
        this.claimToken = token;
        this.leaseUntil = until;
        this.status = Status.IN_PROGRESS;
    }

    public void published(Instant at) {
        this.status = Status.PUBLISHED;
        this.publishedAt = at;
    }
}
