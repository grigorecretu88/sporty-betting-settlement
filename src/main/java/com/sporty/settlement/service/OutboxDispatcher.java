package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.logging.CorrelationIdFilter;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "app.outbox.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboxDispatcher {
    private static final String TYPE = BetSettlementCommandV1.class.getSimpleName();
    private static final String DESTINATION = "bet-settlements";
    private final OutboxClaimService claims;
    private final BetSettlementPublisher publisher;
    private final ObjectMapper mapper;

    public OutboxDispatcher(OutboxClaimService claims, BetSettlementPublisher publisher, ObjectMapper mapper) {
        this.claims = claims;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${app.outbox.dispatcher.delay-ms:500}")
    public void dispatch() {
        // Claim just before each send so queued work does not consume its lease.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        for (int processed = 0; processed < 50 && System.nanoTime() < deadline; processed++) {
            var batch = claims.claimBatch();
            if (batch.isEmpty()) return;
            for (ClaimedOutboxMessage message : batch) dispatchOne(message);
        }
    }

    private void dispatchOne(ClaimedOutboxMessage message) {
        if (!TYPE.equals(message.messageType()) || !DESTINATION.equals(message.destination())) {
            recordFailure(message, "Unsupported outbox destination or message type: "
                    + message.destination() + "/" + message.messageType(), true);
            return;
        }
        try {
            BetSettlementCommandV1 command = mapper.readValue(message.payloadJson(), BetSettlementCommandV1.class);
            if (message.correlationId() == null || message.correlationId().isBlank()) {
                publisher.publish(command);
            } else {
                try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY,
                        message.correlationId())) {
                    publisher.publish(command);
                }
            }

        } catch (Exception failure) {
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            recordFailure(message, error, false);
            log.warn("Outbox publication failed messageId={} destination={} attempt={}",
                    message.id(), message.destination(), message.attempt(), failure);
            return;
        }
        try {
            claims.markSent(message);
            log.info("Outbox publication recorded messageId={} destination={}", message.id(), message.destination());
        } catch (Exception failure) {
            log.error("Outbox publication succeeded but SENT update failed; lease recovery may republish messageId={}",
                    message.id(), failure);
        }
    }
    private void recordFailure(ClaimedOutboxMessage message, String error, boolean terminal) {
        try {
            claims.markFailed(message, error, terminal);
        } catch (Exception failure) {
            log.error("Outbox failure update failed; claim retained for lease recovery messageId={}", message.id(), failure);
        }
    }
}
