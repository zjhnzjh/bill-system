package com.bill.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments", uniqueConstraints = @UniqueConstraint(name = "uk_payment_order", columnNames = "order_id"))
public class Payment {
    @Id
    private String id;
    @Column(name = "order_id", nullable = false)
    private String orderId;
    private BigDecimal amount;
    private String status;
    private String callbackUrl;
    private Instant createdAt;
    private Instant updatedAt;

    protected Payment() {}

    public Payment(String orderId, BigDecimal amount, String callbackUrl) {
        this.id = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.amount = amount;
        this.status = "PENDING";
        this.callbackUrl = callbackUrl;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void succeed() { status = "SUCCEEDED"; updatedAt = Instant.now(); }
    public void refund() { status = "REFUNDED"; updatedAt = Instant.now(); }
    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getCallbackUrl() { return callbackUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
