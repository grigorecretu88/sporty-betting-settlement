package com.sporty.settlement.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.logging.CorrelationIdContext;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.outbox.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class OutboxDispatcher {
    private static final int MAX_MESSAGES_PER_RUN = 50;
    private static final Duration MAX_RUN_DURATION = Duration.ofSeconds(5);

    private final OutboxClaimService claims;
    private final BetSettlementPublisher publisher;
    private final ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${app.outbox.dispatcher.delay-ms:500}")
    public void dispatch() {
        // Claim just before each send so queued work does not consume its lease.
        long deadline = System.nanoTime() + MAX_RUN_DURATION.toNanos();
        for (int processed = 0;
             processed < MAX_MESSAGES_PER_RUN && System.nanoTime() < deadline;
             processed++) {
            var claimed = claims.claimNext();
            if (claimed.isEmpty()) {
                return;
            }
            dispatchOne(claimed.get());
        }
    }

    private void dispatchOne(ClaimedOutboxMessage message) {
        if (!SettlementOutboxContract.MESSAGE_TYPE.equals(message.messageType())
                || !SettlementOutboxContract.LOGICAL_DESTINATION.equals(message.destination())) {
            recordFailure(message, "Unsupported outbox route or message type: "
                    + message.destination() + "/" + message.messageType(), true);
            return;
        }
        try {
            BetSettlementCommandV1 command = mapper.readValue(message.payloadJson(), BetSettlementCommandV1.class);
            CorrelationIdContext.run(message.correlationId(), () -> publisher.publish(command));
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
        } catch (RuntimeException failure) {
            log.error("Outbox publication succeeded but SENT update failed; lease recovery may republish messageId={}",
                    message.id(), failure);
        }
    }

    private void recordFailure(ClaimedOutboxMessage message, String error, boolean terminal) {
        try {
            claims.markFailed(message, error, terminal);
        } catch (RuntimeException failure) {
            log.error("Outbox failure update failed; claim retained for lease recovery messageId={}", message.id(), failure);
        }
    }
}
