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
@Table(name = "order_requests")
public class AsyncOrderRequest {
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
    private String traceId;
    @Enumerated(EnumType.STRING)
    private AsyncOrderStatus status;
    private String orderId;
    @Column(length = 500)
    private String lastError;
    private String topic;
    private Integer kafkaPartition;
    private Long kafkaOffset;
    private int receivedCount;
    private int businessExecutions;
    private Instant acceptedAt;
    private Instant processingAt;
    private Instant completedAt;
    private Instant updatedAt;

    protected AsyncOrderRequest() {}

    public AsyncOrderRequest(String idempotencyKey, String requestFingerprint, String userId, String sku,
                             int quantity, BigDecimal amount, String traceId) {
        this.id = UUID.randomUUID().toString();
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.userId = userId;
        this.sku = sku;
        this.quantity = quantity;
        this.amount = amount;
        this.traceId = traceId;
        this.status = AsyncOrderStatus.ACCEPTED;
        this.acceptedAt = Instant.now();
        this.updatedAt = acceptedAt;
    }

    public void published(String topic, int partition, long offset) {
        this.topic = topic; this.kafkaPartition = partition; this.kafkaOffset = offset;
        if (status == AsyncOrderStatus.ACCEPTED) status = AsyncOrderStatus.QUEUED;
        updatedAt = Instant.now();
    }
    public void received(String topic, int partition, long offset) {
        this.topic = topic; this.kafkaPartition = partition; this.kafkaOffset = offset;
        receivedCount++; status = AsyncOrderStatus.PROCESSING;
        if (processingAt == null) processingAt = Instant.now();
        updatedAt = Instant.now();
    }
    public void duplicateReceived(String topic, int partition, long offset) {
        this.topic = topic; this.kafkaPartition = partition; this.kafkaOffset = offset;
        receivedCount++; updatedAt = Instant.now();
    }
    public void executed() { businessExecutions++; updatedAt = Instant.now(); }
    public void retrying(String error) { status = AsyncOrderStatus.RETRYING; lastError = error; updatedAt = Instant.now(); }
    public void succeeded(String orderId) { this.orderId = orderId; status = AsyncOrderStatus.SUCCEEDED; lastError = null; completedAt = Instant.now(); updatedAt = completedAt; }
    public void rejected(String orderId, String reason) { this.orderId = orderId; status = AsyncOrderStatus.REJECTED; lastError = reason; completedAt = Instant.now(); updatedAt = completedAt; }
    public void deadLetter(String error) { status = AsyncOrderStatus.DEAD_LETTER; lastError = error; completedAt = Instant.now(); updatedAt = completedAt; }
    public void requeue() { status = AsyncOrderStatus.QUEUED; lastError = null; completedAt = null; updatedAt = Instant.now(); }

    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getUserId() { return userId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public BigDecimal getAmount() { return amount; }
    public String getTraceId() { return traceId; }
    public AsyncOrderStatus getStatus() { return status; }
    public String getOrderId() { return orderId; }
    public String getLastError() { return lastError; }
    public String getTopic() { return topic; }
    public Integer getKafkaPartition() { return kafkaPartition; }
    public Long getKafkaOffset() { return kafkaOffset; }
    public int getReceivedCount() { return receivedCount; }
    public int getBusinessExecutions() { return businessExecutions; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getProcessingAt() { return processingAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
