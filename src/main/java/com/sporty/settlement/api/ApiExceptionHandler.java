package com.sporty.settlement.api;

import com.sporty.settlement.api.dto.ErrorResponse;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.logging.CorrelationIdProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Small, stable API error mapping.
 *
 * We intentionally keep the mapping minimal for the assignment:
 * - domain exceptions become a predictable JSON shape
 * - correlationId is echoed for troubleshooting
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    private final ObjectProvider<CorrelationIdProvider> correlationIdProvider;

    public ApiExceptionHandler(ObjectProvider<CorrelationIdProvider> correlationIdProvider) {
        this.correlationIdProvider = correlationIdProvider;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(ApiExceptionHandler::formatFieldError)
                .distinct()
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = "Validation failed";
        }

        // Reuse build() for stable error shape.
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", new IllegalArgumentException(message, ex));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        // Malformed JSON / type mismatch.
        return build(HttpStatus.BAD_REQUEST, "INVALID_JSON", ex);
    }

    @ExceptionHandler(EventOutcomePublishException.class)
    public ResponseEntity<ErrorResponse> handlePublish(EventOutcomePublishException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "EVENT_OUTCOME_PUBLISH_FAILED", ex);
    }

    @ExceptionHandler(BetSettlementException.class)
    public ResponseEntity<ErrorResponse> handleSettlement(BetSettlementException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "BET_SETTLEMENT_FAILED", ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, Exception ex) {
        CorrelationIdProvider provider = correlationIdProvider.getIfAvailable();
        String corrId = provider == null ? null : provider.currentId().orElse(null);
        log.warn("API error code={} status={} corrId={} message={}", code, status.value(), corrId, ex.getMessage(), ex);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, ex.getMessage(), corrId, Instant.now()));
    }

    private static String formatFieldError(FieldError fe) {
        String field = fe.getField();
        String msg = fe.getDefaultMessage();
        if (msg == null || msg.isBlank()) {
            msg = "invalid";
        }
        return field + ": " + msg;
    }
}
