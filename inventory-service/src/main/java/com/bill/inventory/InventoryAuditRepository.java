package com.bill.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InventoryAuditRepository extends JpaRepository<InventoryAudit, Long> {
    List<InventoryAudit> findTop50ByOrderByOccurredAtDesc();
}
