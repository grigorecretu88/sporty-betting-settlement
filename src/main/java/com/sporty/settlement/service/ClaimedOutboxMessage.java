package com.sporty.settlement.service;

public record ClaimedOutboxMessage(String id, String claimToken, String destination,
                                   String messageType, String payloadJson,
                                   String correlationId, int attempt) { }
