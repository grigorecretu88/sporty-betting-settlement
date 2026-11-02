package com.sporty.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import com.sporty.settlement.repository.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes messages to the outbox within the caller transaction.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxMessageRepository repository;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(BetSettlementCommandV1 command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        final String json;
        try {
            json = mapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox payload", e);
        }

        repository.save(
                new OutboxMessageEntity(
                        command.commandId(),
                        "bet-settlements",
                        BetSettlementCommandV1.class.getSimpleName(),
                        json,
                        command.correlationId(),
                        OutboxStatus.PENDING,
                        Instant.now()
                ));
    }
}
