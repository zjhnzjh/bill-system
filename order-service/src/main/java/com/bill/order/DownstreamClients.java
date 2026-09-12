package com.bill.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.util.Map;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Component
public class DownstreamClients {
    private final RestClient inventory;
    private final RestClient payment;

    public DownstreamClients(RestClient.Builder builder,
                             @Value("${services.inventory-url}") String inventoryUrl,
                             @Value("${services.payment-url}") String paymentUrl) {
        this.inventory = builder.clone().baseUrl(inventoryUrl).build();
        this.payment = builder.clone().baseUrl(paymentUrl).build();
    }

    public void reserve(String orderId, String sku, int quantity, String traceId) {
        inventory.post().uri("/api/inventory/reservations")
                .header("X-Trace-Id", traceId)
                .body(Map.of("orderId", orderId, "sku", sku, "quantity", quantity))
                .retrieve().toBodilessEntity();
    }

    public void release(String orderId, String traceId) {
        inventory.post().uri("/api/inventory/reservations/{orderId}/release", orderId)
                .header("X-Trace-Id", traceId).retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "payment")
    @Bulkhead(name = "payment", type = Bulkhead.Type.SEMAPHORE)
    public Map<String, Object> createPayment(String orderId, BigDecimal amount, String callbackUrl, String traceId) {
        return payment.post().uri("/api/payments").header("X-Trace-Id", traceId)
                .body(Map.of("orderId", orderId, "amount", amount, "callbackUrl", callbackUrl))
                .retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "payment")
    @Bulkhead(name = "payment", type = Bulkhead.Type.SEMAPHORE)
    public Map<String, Object> succeedPayment(String paymentId, boolean dropCallback, String traceId) {
        return payment.post().uri(uri -> uri.path("/api/payments/{id}/succeed").queryParam("dropCallback", dropCallback).build(paymentId))
                .header("X-Trace-Id", traceId).retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> paymentByOrder(String orderId, String traceId) {
        return payment.get().uri("/api/payments/orders/{orderId}", orderId)
                .header("X-Trace-Id", traceId).retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> refundPayment(String paymentId, String traceId) {
        return payment.post().uri("/api/payments/{paymentId}/refund", paymentId)
                .header("X-Trace-Id", traceId).retrieve().body(Map.class);
    }
}
