package com.bill.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {
    @Id
    private String orderId;
    private String sku;
    private int quantity;
    private String status;
    private Instant createdAt;

    protected InventoryReservation() {}

    public InventoryReservation(String orderId, String sku, int quantity) {
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.status = "RESERVED";
        this.createdAt = Instant.now();
    }

    public void release() { this.status = "RELEASED"; }
    public String getOrderId() { return orderId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}

