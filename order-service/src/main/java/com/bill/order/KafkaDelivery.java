package com.bill.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "kafka_deliveries")
public class KafkaDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String requestId;
    private String topic;
    private int kafkaPartition;
    private long kafkaOffset;
    private int attempt;
    private String outcome;
    private String detail;
    private Instant occurredAt;

    protected KafkaDelivery() {}
    public KafkaDelivery(String requestId, String topic, int partition, long offset, int attempt, String outcome, String detail) {
        this.requestId = requestId; this.topic = topic; this.kafkaPartition = partition; this.kafkaOffset = offset;
        this.attempt = attempt; this.outcome = outcome; this.detail = detail; this.occurredAt = Instant.now();
    }
    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getTopic() { return topic; }
    public int getKafkaPartition() { return kafkaPartition; }
    public long getKafkaOffset() { return kafkaOffset; }
    public int getAttempt() { return attempt; }
    public String getOutcome() { return outcome; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
