package com.sporty.settlement.domain;

import java.math.BigDecimal;
import java.time.Instant;

public interface BetView {

    String getBetId();

    String getUserId();

    String getEventId();

    String getEventMarketId();

    String getEventWinnerId();

    BigDecimal getBetAmount();

    String getStatus();

    Instant getSettledAt();

    String getOutcomeWinnerId();
}