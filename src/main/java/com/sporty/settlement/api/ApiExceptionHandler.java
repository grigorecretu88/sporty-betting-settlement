package com.sporty.settlement.api;

import com.sporty.settlement.api.dto.ApiErrorResponse;
import com.sporty.settlement.exception.BetSettlementException;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.logging.CorrelationIdProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/** Maps API failures to a stable response shape with a correlation ID. */
@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class ApiExceptionHandler {

    private final ObjectProvider<CorrelationIdProvider> correlationIdProvider;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(ApiExceptionHandler::formatFieldError)
                .distinct()
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = "Validation failed";
        }

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, ex);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body must contain valid JSON", ex);
    }

    @ExceptionHandler(EventOutcomePublishException.class)
    public ResponseEntity<ApiErrorResponse> handlePublish(EventOutcomePublishException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_OUTCOME_PUBLISH_FAILED",
                "Event outcome publication could not be confirmed", ex);
    }

    @ExceptionHandler(BetSettlementException.class)
    public ResponseEntity<ApiErrorResponse> handleSettlement(BetSettlementException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "BET_SETTLEMENT_FAILED", "Bet settlement failed", ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse error) {
            var response = build(HttpStatus.valueOf(error.getStatusCode().value()), "REQUEST_REJECTED",
                    "Request could not be processed", ex);
            return ResponseEntity.status(response.getStatusCode())
                    .headers(error.getHeaders()).body(response.getBody());
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", ex);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message, Exception ex) {
        CorrelationIdProvider provider = correlationIdProvider.getIfAvailable();
        String corrId = provider == null ? null : provider.currentId().orElse(null);
        if (status.is5xxServerError()) {
            log.error("API error code={} status={} corrId={}", code, status.value(), corrId, ex);
        } else {
            log.warn("API request rejected code={} status={} corrId={} message={}",
                    code, status.value(), corrId, message);
        }
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, corrId, Instant.now()));
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
