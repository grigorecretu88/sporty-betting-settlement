package com.sporty.settlement.messaging;

import com.sporty.settlement.domain.BetSettlementCommandV1;

public interface BetSettlementPublisher {
    void publish(BetSettlementCommandV1 cmd);
}
