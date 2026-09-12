package com.bill.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "recovery_jobs", uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "job_type"}))
public class RecoveryJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private String orderId;
    @Column(name = "job_type", nullable = false)
    private String jobType;
    @Column(nullable = false)
    private String status;
    private int attempts;
    private int maxAttempts;
    private Instant nextRunAt;
    @Column(length = 500)
    private String lastError;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;

    protected RecoveryJob() {}

    public RecoveryJob(String orderId, String jobType, String traceId) {
        this.orderId = orderId;
        this.jobType = jobType;
        this.traceId = traceId;
        this.status = "PENDING";
        this.attempts = 0;
        this.maxAttempts = 3;
        this.nextRunAt = Instant.now().plusSeconds(3);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void succeeded() {
        status = "SUCCEEDED";
        lastError = null;
        updatedAt = Instant.now();
    }

    public void failed(Throwable error) {
        attempts++;
        lastError = safe(error);
        status = attempts >= maxAttempts ? "DEAD_LETTER" : "RETRYING";
        nextRunAt = Instant.now().plusSeconds(Math.min(30, 1L << attempts));
        updatedAt = Instant.now();
    }

    public void retryNow() {
        if ("SUCCEEDED".equals(status)) throw new IllegalStateException("A successful job does not need retrying");
        status = "PENDING";
        attempts = 0;
        lastError = null;
        nextRunAt = Instant.now();
        updatedAt = Instant.now();
    }

    private static String safe(Throwable error) {
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return text.length() > 480 ? text.substring(0, 480) : text;
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getJobType() { return jobType; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextRunAt() { return nextRunAt; }
    public String getLastError() { return lastError; }
    public String getTraceId() { return traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
