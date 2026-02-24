
package com.sporty.settlement.api;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/event-outcomes")
public class EventOutcomeController {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public EventOutcomeController(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping
    public void publish(@RequestBody String payload) {
        kafkaTemplate.send("event-outcomes", payload);
    }
}
