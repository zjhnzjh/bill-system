package com.bill.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {
    @Id
    private String sku;
    private int available;
    private int reserved;

    protected InventoryItem() {}

    public InventoryItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
        this.reserved = 0;
    }

    public void reserve(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (available < quantity) throw new InsufficientStockException(sku, quantity, available);
        available -= quantity;
        reserved += quantity;
    }

    public void release(int quantity) {
        if (quantity <= 0 || reserved < quantity) throw new IllegalStateException("invalid release quantity");
        reserved -= quantity;
        available += quantity;
    }

    public void setAvailable(int value) {
        if (value < 0) throw new IllegalArgumentException("available stock cannot be negative");
        this.available = value;
    }

    public String getSku() { return sku; }
    public int getAvailable() { return available; }
    public int getReserved() { return reserved; }
}
