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
        when(claims.claimBatch()).thenReturn(List.of(claimed));

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
        when(claims.claimBatch()).thenReturn(List.of(claimed));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        new OutboxDispatcher(claims, publisher, mapper).dispatch();

        verify(claims).markFailed(eq(claimed), contains("broker down"), eq(false));
        verify(claims, never()).markSent(any());
    }

    private static BetSettlementCommandV1 command() {
        return new BetSettlementCommandV1("cmd-1", "bet-1", "user-1", "event-100", "market-1",
                "team-a", new BigDecimal("10"), "team-a", Instant.parse("2026-02-26T10:00:00Z"), "corr");
    }
}
