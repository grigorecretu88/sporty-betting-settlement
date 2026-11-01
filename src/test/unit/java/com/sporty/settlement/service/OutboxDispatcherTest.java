package com.sporty.settlement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void successfulPublicationIsMarkedOnlyAfterPublisherReturns() throws Exception {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        var command = command();
        var claimed = new ClaimedOutboxMessage("cmd-1", "token", "bet-settlements",
                "BetSettlementCommandV1", mapper.writeValueAsString(command), "corr", 1);
        when(claims.claimBatch()).thenReturn(List.of(claimed), List.of());

        new OutboxDispatcher(claims, publisher, mapper).dispatch();

        var order = inOrder(publisher, claims);
        order.verify(publisher).publish(command);
        order.verify(claims).markSent(claimed);
    }

    @Test
    void failedPublicationReleasesClaimForRetryAndNeverMarksSent() throws Exception {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        var claimed = new ClaimedOutboxMessage("cmd-1", "token", "bet-settlements",
                "BetSettlementCommandV1", mapper.writeValueAsString(command()), "corr", 1);
        when(claims.claimBatch()).thenReturn(List.of(claimed), List.of());
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        new OutboxDispatcher(claims, publisher, mapper).dispatch();

        verify(claims).markFailed(eq(claimed), contains("broker down"), eq(false));
        verify(claims, never()).markSent(any());
    }

    @Test
    void unsupportedDestinationNeverPublishes() throws Exception {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        var claimed = new ClaimedOutboxMessage("cmd-1", "token", "wrong-topic",
                "BetSettlementCommandV1", mapper.writeValueAsString(command()), "corr", 1);
        when(claims.claimBatch()).thenReturn(List.of(claimed), List.of());

        new OutboxDispatcher(claims, publisher, mapper).dispatch();

        verifyNoInteractions(publisher);
        verify(claims).markFailed(eq(claimed), contains("wrong-topic"), eq(true));
    }

    @Test
    void markingFailureAfterPublicationDoesNotMisclassifySendAndContinuesBatch() throws Exception {
        var claims = mock(OutboxClaimService.class);
        var publisher = mock(BetSettlementPublisher.class);
        var first = new ClaimedOutboxMessage("first", "token", "bet-settlements", "BetSettlementCommandV1",
                mapper.writeValueAsString(command()), null, 1);
        var second = new ClaimedOutboxMessage("second", "token", "bet-settlements", "BetSettlementCommandV1",
                mapper.writeValueAsString(command()), null, 1);
        when(claims.claimBatch()).thenReturn(List.of(first), List.of(second), List.of());
        doThrow(new IllegalStateException("expired")).when(claims).markSent(first);
        new OutboxDispatcher(claims, publisher, mapper).dispatch();
        verify(publisher, times(2)).publish(any());
        verify(claims).markSent(second);
        verify(claims, never()).markFailed(any(), anyString(), anyBoolean());
    }

    @Test
    void publicationFailurePartwayThroughWorkDoesNotSkipRemainingCommands() throws Exception {
        var claims = mock(OutboxClaimService.class);
        var publisher = mock(BetSettlementPublisher.class);
        var first = new ClaimedOutboxMessage("first", "token", "bet-settlements", "BetSettlementCommandV1",
                mapper.writeValueAsString(command()), null, 1);
        var second = new ClaimedOutboxMessage("second", "token", "bet-settlements", "BetSettlementCommandV1",
                mapper.writeValueAsString(command()), null, 1);
        var third = new ClaimedOutboxMessage("third", "token", "bet-settlements", "BetSettlementCommandV1",
                mapper.writeValueAsString(command()), null, 1);
        when(claims.claimBatch()).thenReturn(List.of(first), List.of(second), List.of(third), List.of());
        doNothing().doThrow(new IllegalStateException("broker down")).doNothing().when(publisher).publish(any());
        new OutboxDispatcher(claims, publisher, mapper).dispatch();
        verify(publisher, times(3)).publish(any());
        verify(claims).markSent(first);
        verify(claims).markSent(third);
        verify(claims, never()).markSent(second);
        verify(claims).markFailed(eq(second), contains("broker down"), eq(false));
    }

    private static BetSettlementCommandV1 command() {
        return new BetSettlementCommandV1("cmd-1", "bet-1", "user-1", "event-100", "market-1",
                "team-a", new BigDecimal("10"), "team-a", Instant.parse("2026-02-26T10:00:00Z"), "corr");
    }
}
