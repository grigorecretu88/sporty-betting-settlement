package com.sporty.settlement.repository.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "api_publications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiPublicationEntity {
    public enum Status { IN_PROGRESS, PUBLISHED }

    @Id
    @Column(name = "idempotency_key", length = 200)
    private String key;
    @Version
    private Long version;
    @Column(nullable = false, length = 64)
    @Getter
    private String payloadHash;
    @Column(nullable = false, length = 36)
    @Getter
    private String claimToken;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Getter
    private Status status;
    @Column(nullable = false)
    @Getter
    private Instant leaseUntil;

    public ApiPublicationEntity(String key, String payloadHash, String claimToken, Instant leaseUntil) {
        this.key = key;
        this.payloadHash = payloadHash;
        this.claimToken = claimToken;
        this.status = Status.IN_PROGRESS;
        this.leaseUntil = leaseUntil;
    }

    public void reclaim(String claimToken, Instant leaseUntil) {
        this.claimToken = claimToken;
        this.leaseUntil = leaseUntil;
        this.status = Status.IN_PROGRESS;
    }

    public void markPublished() {
        this.status = Status.PUBLISHED;
    }
}
