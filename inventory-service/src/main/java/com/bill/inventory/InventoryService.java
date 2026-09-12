package com.bill.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
    private final InventoryRepository inventory;
    private final ReservationRepository reservations;

    public InventoryService(InventoryRepository inventory, ReservationRepository reservations) {
        this.inventory = inventory;
        this.reservations = reservations;
    }

    @Transactional
    public InventoryReservation reserve(String orderId, String sku, int quantity) {
        var item = inventory.findBySkuForUpdate(sku)
                .orElseThrow(() -> new IllegalArgumentException("Unknown SKU: " + sku));
        var existing = reservations.findById(orderId);
        if (existing.isPresent()) {
            var reservation = existing.get();
            if (!reservation.getSku().equals(sku) || reservation.getQuantity() != quantity) {
                throw new IllegalStateException("Idempotency conflict for order " + orderId);
            }
            return reservation;
        }
        item.reserve(quantity);
        return reservations.save(new InventoryReservation(orderId, sku, quantity));
    }

    @Transactional
    public InventoryReservation release(String orderId) {
        var reservation = reservations.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + orderId));
        if ("RELEASED".equals(reservation.getStatus())) return reservation;
        var item = inventory.findBySkuForUpdate(reservation.getSku())
                .orElseThrow(() -> new IllegalStateException("Inventory item missing"));
        item.release(reservation.getQuantity());
        reservation.release();
        return reservation;
    }
}

