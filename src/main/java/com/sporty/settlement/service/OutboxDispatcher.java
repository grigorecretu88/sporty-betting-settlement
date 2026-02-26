package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import com.sporty.settlement.repository.outbox.OutboxMessageEntity;
import com.sporty.settlement.repository.outbox.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Periodically publishes NEW outbox messages.
 *
 * This keeps the consumer transaction fast and ensures settlement messages are not lost
 * if the app crashes after DB work but before producing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;
    private static final String SUPPORTED_MESSAGE_TYPE = BetSettlementCommandV1.class.getSimpleName();

    private final OutboxMessageRepository repository;
    private final BetSettlementPublisher publisher;
    private final ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${app.outbox.dispatcher.delay-ms:500}")
    @Transactional
    public void dispatch() {
        List<OutboxMessageEntity> batch = repository.findNextNew(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxMessageEntity outboxMessage : batch) {
            try {
                // For this exercise we only have one outbox message type.
                if (!SUPPORTED_MESSAGE_TYPE.equals(outboxMessage.getMessageType())) {
                    outboxMessage.markFailedPermanently("Unsupported messageType=" + outboxMessage.getMessageType());
                    continue;
                }

                BetSettlementCommandV1 cmd = mapper.readValue(outboxMessage.getPayloadJson(), BetSettlementCommandV1.class);
                String correlationId = outboxMessage.getCorrelationId() != null ? outboxMessage.getCorrelationId() : cmd.correlationId();

                if (correlationId == null || correlationId.isBlank()) {
                    publisher.publish(cmd);
                } else {
                    try (org.slf4j.MDC.MDCCloseable ignored = org.slf4j.MDC.putCloseable(
                            com.sporty.settlement.logging.CorrelationIdFilter.MDC_KEY,
                            correlationId
                    )) {
                        publisher.publish(cmd);
                    }
                }
                outboxMessage.markSent(Instant.now());

            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                outboxMessage.markAttemptFailed(msg);

                if (outboxMessage.getAttempts() >= MAX_ATTEMPTS) {
                    outboxMessage.markFailedPermanently("Max attempts reached. Last error: " + msg);
                    log.error("Outbox message permanently failed id={} destination={} type={} attempts={}",
                            outboxMessage.getId(), outboxMessage.getDestination(), outboxMessage.getMessageType(), outboxMessage.getAttempts(), e);
                } else {
                    log.warn("Outbox publish failed id={} destination={} type={} attempts={}",
                            outboxMessage.getId(), outboxMessage.getDestination(), outboxMessage.getMessageType(), outboxMessage.getAttempts(), e);
                }
            }
        }
    }
}
