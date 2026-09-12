package com.bill.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class BillOrder {
    @Id
    private String id;
    @Column(nullable = false, unique = true, length = 120)
    private String idempotencyKey;
    @Column(nullable = false, length = 64)
    private String requestFingerprint;
    private String userId;
    private String sku;
    private int quantity;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private String paymentId;
    private String traceId;
    @Column(length = 500)
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    protected BillOrder() {}

    public BillOrder(String idempotencyKey, String requestFingerprint, String userId, String sku,
                     int quantity, BigDecimal amount, String traceId) {
        this.id = UUID.randomUUID().toString();
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.userId = userId;
        this.sku = sku;
        this.quantity = quantity;
        this.amount = amount;
        this.traceId = traceId;
        this.status = OrderStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void transition(OrderStatus next) {
        boolean valid = switch (status) {
            case CREATED -> next == OrderStatus.STOCK_RESERVED || next == OrderStatus.FAILED;
            case STOCK_RESERVED -> next == OrderStatus.PENDING_PAYMENT || next == OrderStatus.MANUAL_REVIEW || next == OrderStatus.CANCELLED;
            case PENDING_PAYMENT -> next == OrderStatus.PAID || next == OrderStatus.CANCELLED || next == OrderStatus.MANUAL_REVIEW;
            case PAID -> next == OrderStatus.FULFILLED || next == OrderStatus.REFUNDED;
            case FAILED, CANCELLED, REFUNDED, FULFILLED, MANUAL_REVIEW -> next == status;
        };
        if (!valid) throw new IllegalStateException("Illegal order transition: " + status + " -> " + next);
        status = next;
        updatedAt = Instant.now();
    }

    public void attachPayment(String paymentId) { this.paymentId = paymentId; this.updatedAt = Instant.now(); }
    public void fail(String reason, OrderStatus terminal) { this.failureReason = reason; transition(terminal); }
    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getUserId() { return userId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public BigDecimal getAmount() { return amount; }
    public OrderStatus getStatus() { return status; }
    public String getPaymentId() { return paymentId; }
    public String getTraceId() { return traceId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

