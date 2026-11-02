package com.sporty.settlement.service;

import com.sporty.settlement.domain.BetSettlementCommandV1;

final class SettlementOutboxContract {
    static final String LOGICAL_DESTINATION = "bet-settlements";
    static final String MESSAGE_TYPE = BetSettlementCommandV1.class.getSimpleName();

    private SettlementOutboxContract() {
    }
}
