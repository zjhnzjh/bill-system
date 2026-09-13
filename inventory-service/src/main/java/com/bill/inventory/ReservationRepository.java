package com.bill.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReservationRepository extends JpaRepository<InventoryReservation, String> {
    List<InventoryReservation> findTop100ByOrderByCreatedAtDesc();
    boolean existsBySkuAndStatus(String sku, String status);
}
