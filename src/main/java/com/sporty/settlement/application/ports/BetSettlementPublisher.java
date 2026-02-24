package com.sporty.settlement.application.ports;

import com.sporty.settlement.domain.BetSettlementCommandV1;

public interface BetSettlementPublisher {
    void publish(BetSettlementCommandV1 cmd);
}
