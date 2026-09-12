package com.bill.payment;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PaymentFaults {
    private final AtomicInteger failQueries = new AtomicInteger();
    private final AtomicInteger failCalls = new AtomicInteger();

    public Map<String, Integer> configure(int queryFailures, int callFailures) {
        failQueries.set(Math.max(0, queryFailures));
        failCalls.set(Math.max(0, callFailures));
        return state();
    }

    public Map<String, Integer> state() {
        return Map.of("failNextQueries", failQueries.get(), "failNextCalls", failCalls.get());
    }

    public void beforeQuery() {
        while (true) {
            int remaining = failQueries.get();
            if (remaining <= 0) return;
            if (failQueries.compareAndSet(remaining, remaining - 1)) {
                throw new IllegalStateException("Injected payment query failure");
            }
        }
    }

    public void beforeCall() {
        while (true) {
            int remaining = failCalls.get();
            if (remaining <= 0) return;
            if (failCalls.compareAndSet(remaining, remaining - 1)) {
                throw new IllegalStateException("Injected payment service failure");
            }
        }
    }
}
