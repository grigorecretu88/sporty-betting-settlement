package com.sporty.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes messages to the outbox within the caller transaction.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxMessageRepository repository;
    private final ObjectMapper mapper;

    public String enqueue(String destination, Object payload) {
        return enqueue(destination, payload, null);
    }

    public String enqueue(String destination, Object payload, String correlationId) {
        return enqueue(UUID.randomUUID().toString(), destination, payload, correlationId);
    }

    public String enqueue(String id, String destination, Object payload, String correlationId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }

        final String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox payload", e);
        }

        repository.save(
                new OutboxMessageEntity(
                        id,
                        destination,
                        payload.getClass().getSimpleName(),
                        json,
                        correlationId,
                        OutboxStatus.PENDING,
                        Instant.now()
                ));
        return id;
    }
}
