package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.messaging.EventOutcomeProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublishEventOutcomeServiceTest {
    @Test
    void acknowledgedSendWithFailedCompletionRetainsClaimForRecovery() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var claim = new ApiPublicationClaimService.Claim(ApiPublicationClaimService.Status.CLAIMED, "key", "token");
        when(claims.claim(eq("key"), anyString())).thenReturn(claim);
        var completionFailure = new IllegalStateException("expired");
        doThrow(completionFailure).when(claims).complete(claim);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);
        assertThatThrownBy(() -> service.publish(new PublishEventOutcomeRequest("event", "name", "winner"), "key", null))
                .isInstanceOf(EventOutcomePublishException.class).hasMessageContaining("Kafka accepted")
                .hasCause(completionFailure);
        verify(claims, never()).release(any());
    }

    @Test
    void cleanupFailureDoesNotReplaceBrokerFailure() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var claim = new ApiPublicationClaimService.Claim(ApiPublicationClaimService.Status.CLAIMED, "key", "token");
        when(claims.claim(eq("key"), anyString())).thenReturn(claim);
        var original = new EventOutcomePublishException("broker unavailable");
        doThrow(original).when(producer).send(anyString(), anyString(), isNull());
        doThrow(new IllegalStateException("expired")).when(claims).release(claim);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);
        assertThatThrownBy(() -> service.publish(new PublishEventOutcomeRequest("event", "name", "winner"), "key", null))
                .isSameAs(original).hasSuppressedException(new IllegalStateException("expired"));
    }

    @Test
    void marksIdempotencyClaimCompleteOnlyAfterBrokerAcknowledgement() {
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        ApiPublicationClaimService claims = mock(ApiPublicationClaimService.class);
        var claim = new ApiPublicationClaimService.Claim(ApiPublicationClaimService.Status.CLAIMED, "key", "token");
        when(claims.claim(eq("key"), anyString())).thenReturn(claim);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        service.publish(new PublishEventOutcomeRequest("event-100", "Derby", "team-a"), "key", "corr");

        var order = inOrder(producer, claims);
        order.verify(producer).send(eq("event-100"), anyString(), eq("corr"));
        order.verify(claims).complete(claim);
    }

    @Test
    void brokerFailureReleasesClaimAndDoesNotRecordSuccess() {
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        ApiPublicationClaimService claims = mock(ApiPublicationClaimService.class);
        var claim = new ApiPublicationClaimService.Claim(ApiPublicationClaimService.Status.CLAIMED, "key", "token");
        when(claims.claim(eq("key"), anyString())).thenReturn(claim);
        var failure = new EventOutcomePublishException("Kafka unavailable");
        doThrow(failure).when(producer).send(anyString(), anyString(), anyString());
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event-100", "Derby", "team-a"), "key", "corr"))
                .isSameAs(failure);
        verify(claims).release(claim);
        verify(claims, never()).complete(any());
    }

    @Test
    void alreadyPublishedReplayDoesNotSendAgain() {
        ApiPublicationClaimService claims = mock(ApiPublicationClaimService.class);
        when(claims.claim(eq("key"), anyString())).thenReturn(new ApiPublicationClaimService.Claim(
                ApiPublicationClaimService.Status.ALREADY_PUBLISHED, "key", null));
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        new PublishEventOutcomeService(producer, new ObjectMapper(), claims).publish(
                new PublishEventOutcomeRequest("event-100", "Derby", "team-a"), "key", "corr");
        verifyNoInteractions(producer);
    }

    @Test
    void oversizedIdempotencyKeyIsAClientErrorAndNeverPublishes() {
        EventOutcomeProducer producer = mock(EventOutcomeProducer.class);
        ApiPublicationClaimService claims = mock(ApiPublicationClaimService.class);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event-100", "Derby", "team-a"), "x".repeat(201), "corr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
        verifyNoInteractions(producer, claims);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingOrBlankKeyPublishesWithoutClaim(String key) {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        service.publish(new PublishEventOutcomeRequest(" event ", " name ", " winner "), key, "corr");

        verify(producer).send("event",
                "{\"eventId\":\"event\",\"eventName\":\"name\",\"eventWinnerId\":\"winner\"}", "corr");
        verifyNoInteractions(claims);
    }

    @Test
    void concurrentInsertRetriesAcquisitionWithNormalizedKeyAndSamePayloadHash() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var claim = new ApiPublicationClaimService.Claim(ApiPublicationClaimService.Status.CLAIMED, "key", "token");
        when(claims.claim(eq("key"), anyString()))
                .thenThrow(new DataIntegrityViolationException("concurrent insert")).thenReturn(claim);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        service.publish(new PublishEventOutcomeRequest("event", "name", "winner"), " key ", "corr");

        var hashes = org.mockito.ArgumentCaptor.forClass(String.class);
        var order = inOrder(claims, producer);
        order.verify(claims, times(2)).claim(eq("key"), hashes.capture());
        assertThat(hashes.getAllValues()).containsExactly(
                "da82234aca3f9ac670ebaea9f670b1c932c4e0fe500f3360a56f2e79dce10d60", "da82234aca3f9ac670ebaea9f670b1c932c4e0fe500f3360a56f2e79dce10d60");
        order.verify(producer).send(eq("event"), anyString(), eq("corr"));
        order.verify(claims).complete(claim);
        verify(claims, never()).release(any());
    }

    @Test
    void concurrentInsertFollowedByPublishedClaimDoesNotSend() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        when(claims.claim(eq("key"), anyString()))
                .thenThrow(new DataIntegrityViolationException("concurrent insert"))
                .thenReturn(new ApiPublicationClaimService.Claim(
                        ApiPublicationClaimService.Status.ALREADY_PUBLISHED, "key", null));

        new PublishEventOutcomeService(producer, new ObjectMapper(), claims).publish(
                new PublishEventOutcomeRequest("event", "name", "winner"), "key", "corr");

        verify(claims, times(2)).claim(eq("key"), anyString());
        verify(claims, never()).complete(any());
        verify(claims, never()).release(any());
        verifyNoInteractions(producer);
    }

    @Test
    void inProgressClaimFailsWithoutSendingOrChangingOwnership() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        when(claims.claim(eq("key"), anyString())).thenReturn(new ApiPublicationClaimService.Claim(
                ApiPublicationClaimService.Status.IN_PROGRESS, "key", null));
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event", "name", "winner"), "key", "corr"))
                .isInstanceOf(EventOutcomePublishException.class).hasMessageContaining("still in progress");
        verifyNoInteractions(producer);
        verify(claims, never()).complete(any());
        verify(claims, never()).release(any());
    }

    @Test
    void repeatedClaimFailureIsWrappedAfterOnlyOneRecoveryAttempt() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var failure = new DataIntegrityViolationException("database unavailable");
        when(claims.claim(eq("key"), anyString())).thenThrow(failure);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event", "name", "winner"), "key", null))
                .isInstanceOf(EventOutcomePublishException.class).hasCause(failure);
        verify(claims, times(2)).claim(eq("key"), anyString());
        verifyNoInteractions(producer);
    }

    @Test
    void payloadConflictRemainsClientError() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var failure = new IllegalArgumentException("Idempotency-Key was already used for a different payload");
        when(claims.claim(eq("key"), anyString())).thenThrow(failure);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event", "name", "winner"), "key", null)).isSameAs(failure);
        verifyNoInteractions(producer);
    }

    @Test
    void serializationFailureIsWrappedBeforeClaimOrSend() throws Exception {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var mapper = mock(ObjectMapper.class);
        var failure = new JsonProcessingException("cannot serialize") {};
        when(mapper.writeValueAsString(any())).thenThrow(failure);
        var service = new PublishEventOutcomeService(producer, mapper, claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event", "name", "winner"), "key", null))
                .isInstanceOf(EventOutcomePublishException.class)
                .hasMessage("Failed to publish event outcome for eventId=event").hasCause(failure);
        verifyNoInteractions(producer, claims);
    }

    @Test
    void unkeyedInfrastructureFailureUsesSameServerErrorMapping() {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var failure = new IllegalStateException("Kafka unavailable");
        doThrow(failure).when(producer).send(anyString(), anyString(), isNull());
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event", "name", "winner"), null, null))
                .isInstanceOf(EventOutcomePublishException.class).hasCause(failure);
        verifyNoInteractions(claims);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "key")
    void interruptedPublicationPreservesFailureAndInterruptFlag(String key) {
        var producer = mock(EventOutcomeProducer.class);
        var claims = mock(ApiPublicationClaimService.class);
        var claim = new ApiPublicationClaimService.Claim(ApiPublicationClaimService.Status.CLAIMED, "key", "token");
        when(claims.claim(eq("key"), anyString())).thenReturn(claim);
        var failure = new EventOutcomePublishException("Interrupted while awaiting Kafka acknowledgement",
                new InterruptedException());
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw failure;
        }).when(producer).send(anyString(), anyString(), isNull());
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        try {
            assertThatThrownBy(() -> service.publish(
                    new PublishEventOutcomeRequest("event", "name", "winner"), key, null)).isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            if (key == null) {
                verifyNoInteractions(claims);
            } else {
                verify(claims).release(claim);
                verify(claims, never()).complete(any());
            }
        } finally {
            Thread.interrupted();
        }
    }

}
