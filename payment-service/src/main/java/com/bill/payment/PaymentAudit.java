package com.bill.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "payment_audit")
public class PaymentAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String paymentId;
    private String orderId;
    private String action;
    private String operatorName;
    @Column(length = 500)
    private String detail;
    private Instant occurredAt;

    protected PaymentAudit() {}
    public PaymentAudit(String paymentId, String orderId, String action, String operatorName, String detail) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.action = action;
        this.operatorName = operatorName;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }
    public Long getId() { return id; }
    public String getPaymentId() { return paymentId; }
    public String getOrderId() { return orderId; }
    public String getAction() { return action; }
    public String getOperatorName() { return operatorName; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
