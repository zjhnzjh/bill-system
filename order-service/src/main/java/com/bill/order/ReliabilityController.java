package com.bill.order;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/reliability")
public class ReliabilityController {
    private final CircuitBreakerRegistry breakers;

    public ReliabilityController(CircuitBreakerRegistry breakers) {
        this.breakers = breakers;
    }

    @GetMapping("/circuit-breakers/payment")
    public Map<String, Object> paymentCircuit() {
        var breaker = breakers.circuitBreaker("payment");
        var metrics = breaker.getMetrics();
        return Map.of(
                "name", breaker.getName(),
                "state", breaker.getState().name(),
                "bufferedCalls", metrics.getNumberOfBufferedCalls(),
                "failedCalls", metrics.getNumberOfFailedCalls(),
                "notPermittedCalls", metrics.getNumberOfNotPermittedCalls(),
                "failureRate", metrics.getFailureRate());
    }

    @PostMapping("/circuit-breakers/payment/reset")
    public Map<String, Object> resetPaymentCircuit() {
        breakers.circuitBreaker("payment").reset();
        return paymentCircuit();
    }
}
