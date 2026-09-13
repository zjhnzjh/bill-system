package com.bill.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "inventory_audit")
public class InventoryAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sku;
    private String action;
    private String operatorName;
    @Column(length = 500)
    private String detail;
    private Instant occurredAt;

    protected InventoryAudit() {}

    public InventoryAudit(String sku, String action, String operatorName, String detail) {
        this.sku = sku;
        this.action = action;
        this.operatorName = operatorName;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getAction() { return action; }
    public String getOperatorName() { return operatorName; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
