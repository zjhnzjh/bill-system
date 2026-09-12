package com.bill.inventory;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InventoryFaults {
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicLong delayNextMs = new AtomicLong();

    public Map<String, Object> configure(int failures, long delayMs) {
        failNext.set(Math.max(0, failures));
        delayNextMs.set(Math.max(0, Math.min(delayMs, 10_000)));
        return state();
    }

    public Map<String, Object> state() {
        return Map.of("failNext", failNext.get(), "delayNextMs", delayNextMs.get());
    }

    public void beforeReserve() {
        long delay = delayNextMs.getAndSet(0);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Injected inventory timeout after " + delay + " ms");
        }
        while (true) {
            int remaining = failNext.get();
            if (remaining <= 0) return;
            if (failNext.compareAndSet(remaining, remaining - 1)) {
                throw new IllegalStateException("Injected inventory reservation failure");
            }
        }
    }
}
