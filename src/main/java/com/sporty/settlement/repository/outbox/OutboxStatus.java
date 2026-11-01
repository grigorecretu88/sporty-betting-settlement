package com.sporty.settlement.repository.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED
}
