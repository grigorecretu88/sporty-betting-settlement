
package com.sporty.settlement.infrastructure.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventOutcomeListener {

    @KafkaListener(topics = "event-outcomes", groupId = "settlement-group")
    public void listen(String message) {
        System.out.println("Received event outcome: " + message);
    }
}
