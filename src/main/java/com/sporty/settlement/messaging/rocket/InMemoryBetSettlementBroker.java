package com.sporty.settlement.messaging.rocket;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

/**
 * Simulates a RocketMQ broker in-process.
 *
 * The mock producer enqueues serialised JSON; the mock consumer drains it.
 * This gives a realistic end-to-end demonstration without a real RocketMQ instance.
 */
@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "mock", matchIfMissing = true)
public class InMemoryBetSettlementBroker {

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Set<String> deadLetters = ConcurrentHashMap.newKeySet();
    private static final int MAX_DELIVERY_ATTEMPTS = 5;

    public void enqueue(String json) {
        queue.offer(json);
    }

    public String poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public void retry(String json) {
        int attempt = failures.computeIfAbsent(json, ignored -> new AtomicInteger()).incrementAndGet();
        if (attempt < MAX_DELIVERY_ATTEMPTS) {
            queue.offer(json);
        } else {
            deadLetters.add(json);
        }
    }

    public int deadLetterCount() {
        return deadLetters.size();
    }
}
