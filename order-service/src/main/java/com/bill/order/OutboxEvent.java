package com.bill.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    private String id;
    private String aggregateId;
    private String topic;
    private String messageKey;
    @Column(columnDefinition = "TEXT")
    private String payload;
    private String status;
    private int publishAttempts;
    private Integer kafkaPartition;
    private Long kafkaOffset;
    @Column(length = 500)
    private String lastError;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateId, String topic, String messageKey, String payload) {
        this.id = UUID.randomUUID().toString(); this.aggregateId = aggregateId; this.topic = topic;
        this.messageKey = messageKey; this.payload = payload; this.status = "PENDING"; this.createdAt = Instant.now();
    }

    public void published(int partition, long offset) { status = "PUBLISHED"; publishAttempts++; kafkaPartition = partition; kafkaOffset = offset; lastError = null; publishedAt = Instant.now(); }
    public void failed(String error) { publishAttempts++; lastError = error; }
    public String getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getPublishAttempts() { return publishAttempts; }
    public Integer getKafkaPartition() { return kafkaPartition; }
    public Long getKafkaOffset() { return kafkaOffset; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
