package com.sporty.settlement.service;

public record OutcomeProcessingResult(Status status, int settlementCount) {
    public enum Status { ACCEPTED, DUPLICATE, CONFLICT }

    public static OutcomeProcessingResult accepted(int count) {
        return new OutcomeProcessingResult(Status.ACCEPTED, count);
    }

    public static OutcomeProcessingResult duplicate() {
        return new OutcomeProcessingResult(Status.DUPLICATE, 0);
    }

    public static OutcomeProcessingResult conflict() {
        return new OutcomeProcessingResult(Status.CONFLICT, 0);
    }
}
