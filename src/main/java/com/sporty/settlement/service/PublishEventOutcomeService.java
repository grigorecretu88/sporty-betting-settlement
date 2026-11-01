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
                return;
            }

            String normalizedKey = idempotencyKey.trim();
            if (normalizedKey.length() > 200) {
                throw new IllegalArgumentException("Idempotency-Key must not exceed 200 characters");
            }
            String payloadHash = sha256(json);
            ApiPublicationClaimService.Claim claim;
            try {
                claim = claims.claim(normalizedKey, payloadHash);
            } catch (DataIntegrityViolationException concurrentInsert) {
                // The competing transaction has now established the unique key. Re-read it in
                // a fresh transaction; do not continue inside the failed claim transaction.
                claim = claims.claim(normalizedKey, payloadHash);
            }
            if (claim.status() == ApiPublicationClaimService.Status.ALREADY_PUBLISHED) {
                log.info("HTTP publication replay suppressed eventId={} idempotencyKey={}",
                        outcome.eventId(), normalizedKey);
                return;
            }
            if (claim.status() == ApiPublicationClaimService.Status.IN_PROGRESS) {
                throw new EventOutcomePublishException("A request with this Idempotency-Key is still in progress");
            }
            try {
                producer.send(outcome.eventId(), json, correlationId);
                claims.complete(claim);
            } catch (Exception failure) {
                // A timeout may occur after Kafka accepted the record. Releasing permits a retry;
                // downstream logical-event idempotency makes that ambiguity safe.
                claims.release(claim);
                throw failure;
            }
        } catch (EventOutcomePublishException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new EventOutcomePublishException("Failed to publish event outcome for eventId="
                    + outcome.eventId(), failure);
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
