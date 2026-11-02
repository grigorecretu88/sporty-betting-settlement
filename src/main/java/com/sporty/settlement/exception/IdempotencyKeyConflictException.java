package com.sporty.settlement.exception;

/**
 * Indicates that a client reused an idempotency key for a different request payload.
 */
public class IdempotencyKeyConflictException extends IllegalArgumentException {

    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
