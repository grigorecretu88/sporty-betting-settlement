package com.sporty.settlement.api;

import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.logging.CorrelationIdProvider;
import com.sporty.settlement.service.PublishEventOutcomeService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/event-outcomes")
@Validated
@Slf4j
public class EventOutcomeController {

    /**
     * An explicit key suppresses completed publication retries during this database lifetime.
     * Ambiguous broker outcomes can still be republished.
     */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PublishEventOutcomeService publishService;
    private final CorrelationIdProvider correlationIdProvider;

    public EventOutcomeController(PublishEventOutcomeService publishService,
                                  CorrelationIdProvider correlationIdProvider) {
        this.publishService = publishService;
        this.correlationIdProvider = correlationIdProvider;
    }

    @PostMapping
    public ResponseEntity<Void> publish(
            @Valid @RequestBody PublishEventOutcomeRequest req,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
    ) {
        String correlationId = correlationIdProvider.currentId().orElse(null);
        publishService.publish(req, idempotencyKey, correlationId);

        log.info("Accepted event outcome publish request eventId={} corrId={}", req.eventId(), correlationId);
        return ResponseEntity.accepted().build();
    }
}
