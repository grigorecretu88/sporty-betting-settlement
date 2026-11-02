package com.sporty.settlement.messaging.rocket;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** In-process transport simulation; queue and terminal failures are not durable. */
@Component
@ConditionalOnProperty(name = "app.rocketmq.mode", havingValue = "mock", matchIfMissing = true)
public class InMemoryBetSettlementBroker {
    public record Delivery(String json, int attempt, long dueNanos) implements Delayed {
        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(dueNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }
        @Override public int compareTo(Delayed other) {
            return Long.compare(dueNanos, ((Delivery) other).dueNanos);
        }
    }
    private final DelayQueue<Delivery> queue = new DelayQueue<>();
    private final ConcurrentLinkedQueue<Delivery> deadLetters = new ConcurrentLinkedQueue<>();

    public void enqueue(String json) {
        queue.offer(new Delivery(json, 1, System.nanoTime()));
    }
    public Delivery pollDelivery(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }
    void returnUnconsumed(Delivery delivery) {
        queue.offer(delivery);
    }
    public String poll(long timeout, TimeUnit unit) throws InterruptedException {
        Delivery delivery = pollDelivery(timeout, unit);
        return delivery == null ? null : delivery.json();
    }
    public boolean retry(Delivery delivery) {
        if (delivery.attempt() >= 5) {
            deadLetters.add(delivery);
            return false;
        }
        queue.offer(new Delivery(delivery.json(), delivery.attempt() + 1,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100L * delivery.attempt())));
        return true;
    }
    public int deadLetterCount() { return deadLetters.size(); }
}
