package com.bill.order;

import java.math.BigDecimal;

public record OrderCommand(String eventId, String requestId, String idempotencyKey, String userId,
                           String sku, int quantity, BigDecimal amount, String traceId, int attempt) {
    public OrderCommand nextAttempt() {
        return new OrderCommand(eventId, requestId, idempotencyKey, userId, sku, quantity, amount, traceId, attempt + 1);
    }
}
