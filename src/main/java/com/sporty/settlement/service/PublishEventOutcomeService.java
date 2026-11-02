package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.domain.EventOutcomeV1;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.messaging.EventOutcomeProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@Slf4j
public class PublishEventOutcomeService {
    private final EventOutcomeProducer producer;
    private final ObjectMapper mapper;
    private final ApiPublicationClaimService claims;

    public PublishEventOutcomeService(EventOutcomeProducer producer, ObjectMapper mapper,
                                      ApiPublicationClaimService claims) {
        this.producer = producer;
        this.mapper = mapper;
        this.claims = claims;
    }

    public void publish(PublishEventOutcomeRequest request, String idempotencyKey, String correlationId) {
        EventOutcomeV1 outcome = new EventOutcomeV1(request.eventId(), request.eventName(), request.eventWinnerId());
        try {
            String json = mapper.writeValueAsString(outcome);
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                producer.send(outcome.eventId(), json, correlationId);
            } else {
                publishIdempotently(outcome.eventId(), json, idempotencyKey, correlationId);
            }
        } catch (EventOutcomePublishException | IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new EventOutcomePublishException("Failed to publish event outcome for eventId="
                    + outcome.eventId(), failure);
        }
    }

    private void publishIdempotently(String eventId, String json, String idempotencyKey, String correlationId) {
        String normalizedKey = idempotencyKey.trim();
        if (normalizedKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 200 characters");
        }

        ApiPublicationClaimService.Claim claim = acquireClaim(normalizedKey, sha256(json));
        if (claim.status() == ApiPublicationClaimService.Status.ALREADY_PUBLISHED) {
            log.info("HTTP publication replay suppressed eventId={} idempotencyKey={}", eventId, normalizedKey);
            return;
        }
        if (claim.status() == ApiPublicationClaimService.Status.IN_PROGRESS) {
            throw new EventOutcomePublishException("A request with this Idempotency-Key is still in progress");
        }

        try {
            producer.send(eventId, json, correlationId);
        } catch (Exception failure) {
            releaseAfterFailure(claim, normalizedKey, failure);
            throw failure;
        }
        completePublication(claim, eventId, normalizedKey);
    }

    private ApiPublicationClaimService.Claim acquireClaim(String key, String payloadHash) {
        try {
            return claims.claim(key, payloadHash);
        } catch (DataIntegrityViolationException concurrentInsert) {
            // Re-read through the transactional bean after the failed insert transaction has ended.
            return claims.claim(key, payloadHash);
        }
    }

    private void releaseAfterFailure(ApiPublicationClaimService.Claim claim, String key, Exception failure) {
        // A timeout can follow broker acceptance. Downstream idempotency makes a publication retry safe.
        try {
            claims.release(claim);
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.warn("Publication claim cleanup failed key={}; lease recovery remains available", key, cleanupFailure);
        }
    }

    private void completePublication(ApiPublicationClaimService.Claim claim, String eventId, String key) {
        try {
            claims.complete(claim);
        } catch (Exception completionFailure) {
            log.error("Kafka acknowledged eventId={} but publication claim completion failed key={}; retry may republish",
                    eventId, key, completionFailure);
            throw new EventOutcomePublishException("Kafka accepted outcome but idempotency completion failed", completionFailure);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
