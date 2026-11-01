package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.api.dto.PublishEventOutcomeRequest;
import com.sporty.settlement.exception.EventOutcomePublishException;
import com.sporty.settlement.messaging.EventOutcomeProducer;
import org.junit.jupiter.api.Test;

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
        doThrow(new IllegalStateException("expired")).when(claims).complete(claim);
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);
        assertThatThrownBy(() -> service.publish(new PublishEventOutcomeRequest("event", "name", "winner"), "key", null))
                .isInstanceOf(EventOutcomePublishException.class).hasMessageContaining("Kafka accepted");
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
        doThrow(new EventOutcomePublishException("Kafka unavailable")).when(producer).send(anyString(), anyString(), anyString());
        var service = new PublishEventOutcomeService(producer, new ObjectMapper(), claims);

        assertThatThrownBy(() -> service.publish(
                new PublishEventOutcomeRequest("event-100", "Derby", "team-a"), "key", "corr"))
                .isInstanceOf(EventOutcomePublishException.class);
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
}
