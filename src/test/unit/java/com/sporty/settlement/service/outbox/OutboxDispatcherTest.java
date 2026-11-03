package com.sporty.settlement.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.settlement.domain.BetSettlementCommandV1;
import com.sporty.settlement.messaging.BetSettlementPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void successfulPublicationIsMarkedOnlyAfterPublisherReturns() throws Exception {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        var command = command("cmd-1");
        var claimed = claimed("cmd-1");
        when(claims.claimNext()).thenReturn(Optional.of(claimed)).thenReturn(Optional.empty());

        new OutboxDispatcher(claims, publisher, mapper).dispatch();

        var order = inOrder(publisher, claims);
        order.verify(publisher).publish(command);
        order.verify(claims).markSent(claimed);
    }

    @Test
    void failedPublicationReleasesClaimForRetryAndNeverMarksSent() throws Exception {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        var claimed = claimed("cmd-1");
        when(claims.claimNext()).thenReturn(Optional.of(claimed)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        new OutboxDispatcher(claims, publisher, mapper).dispatch();

        verify(claims).markFailed(eq(claimed), contains("broker down"), eq(false));
        verify(claims, never()).markSent(any());
    }

    @Test
    void unsupportedDestinationNeverPublishes() throws Exception {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        BetSettlementPublisher publisher = mock(BetSettlementPublisher.class);
        var claimed = claimed("cmd-1", "wrong-topic");
        when(claims.claimNext()).thenReturn(Optional.of(claimed)).thenReturn(Optional.empty());

        new OutboxDispatcher(claims, publisher, mapper).dispatch();

        verifyNoInteractions(publisher);
        verify(claims).markFailed(eq(claimed), contains("wrong-topic"), eq(true));
    }

    @Test
    void markingFailureAfterPublicationDoesNotMisclassifySendAndContinuesBatch() throws Exception {
        var claims = mock(OutboxClaimService.class);
        var publisher = mock(BetSettlementPublisher.class);
        var first = claimed("first");
        var second = claimed("second");
        when(claims.claimNext())
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());
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
        var first = claimed("first");
        var second = claimed("second");
        var third = claimed("third");
        when(claims.claimNext())
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.of(third))
                .thenReturn(Optional.empty());
        doNothing().doThrow(new IllegalStateException("broker down")).doNothing().when(publisher).publish(any());
        new OutboxDispatcher(claims, publisher, mapper).dispatch();
        verify(publisher, times(3)).publish(any());
        verify(claims).markSent(first);
        verify(claims).markSent(third);
        verify(claims, never()).markSent(second);
        verify(claims).markFailed(eq(second), contains("broker down"), eq(false));
    }

    private ClaimedOutboxMessage claimed(String id) throws Exception {
        return claimed(id, SettlementOutboxContract.LOGICAL_DESTINATION);
    }

    private ClaimedOutboxMessage claimed(String id, String destination) throws Exception {
        return new ClaimedOutboxMessage(id, "token", destination, SettlementOutboxContract.MESSAGE_TYPE,
                mapper.writeValueAsString(command(id)), "corr", 1);
    }

    private static BetSettlementCommandV1 command(String commandId) {
        return new BetSettlementCommandV1(commandId, "bet-1", "user-1", "event-100", "market-1",
                "team-a", new BigDecimal("10"), "team-a", Instant.parse("2026-02-26T10:00:00Z"), "corr");
    }
}
