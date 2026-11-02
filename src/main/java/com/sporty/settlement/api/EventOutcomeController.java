package com.sporty.settlement.api;

import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.logging.CorrelationIdProvider;
import com.sporty.settlement.service.PublishEventOutcomeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/event-outcomes")
@Slf4j
@RequiredArgsConstructor
public class EventOutcomeController {

    /**
     * Idempotency-Key prevents duplicate Kafka publications after a confirmed success.
     * A timeout may still result in duplicate messages, which downstream processing handles.
     */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PublishEventOutcomeService publishService;
    private final CorrelationIdProvider correlationIdProvider;

    @PostMapping
    public ResponseEntity<Void> publish(
            @Valid @RequestBody PublishEventOutcomeRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
    ) {
        String correlationId = correlationIdProvider.currentId().orElse(null);
        publishService.publish(request, idempotencyKey, correlationId);

        log.info("Accepted event outcome publish request eventId={} corrId={}",
                request.eventId(), correlationId);
        return ResponseEntity.accepted().build();
    }
}
