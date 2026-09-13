package com.bill.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class KafkaFaults {
    private final AtomicInteger failNext = new AtomicInteger();
    private volatile long consumerDelayMs;
    private volatile boolean paused;

    public KafkaFaults(@Value("${kafka-demo.consumer-delay-ms:0}") long delay) { consumerDelayMs = delay; }
    public boolean consumeFailure() {
        while (true) {
            int value = failNext.get();
            if (value <= 0) return false;
            if (failNext.compareAndSet(value, value - 1)) return true;
        }
    }
    public void configure(int failures, long delay) {
        failNext.set(Math.max(0, failures)); consumerDelayMs = Math.max(0, Math.min(delay, 3000));
    }
    public void setPaused(boolean value) { paused = value; }
    public boolean isPaused() { return paused; }
    public long delayMs() { return consumerDelayMs; }
    public Map<String, Object> state() { return Map.of("failNext", failNext.get(), "consumerDelayMs", consumerDelayMs, "paused", paused); }
}
