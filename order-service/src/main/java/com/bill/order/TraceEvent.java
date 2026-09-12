package com.bill.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "trace_events")
public class TraceEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String traceId;
    private String serviceName;
    private String operation;
    private String outcome;
    private long durationMs;
    private String detail;
    private Instant occurredAt;

    protected TraceEvent() {}

    public TraceEvent(String traceId, String serviceName, String operation, String outcome, long durationMs, String detail) {
        this.traceId = traceId;
        this.serviceName = serviceName;
        this.operation = operation;
        this.outcome = outcome;
        this.durationMs = durationMs;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getServiceName() { return serviceName; }
    public String getOperation() { return operation; }
    public String getOutcome() { return outcome; }
    public long getDurationMs() { return durationMs; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}

