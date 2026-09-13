package com.bill.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop25ByStatusOrderByCreatedAtAsc(String status);
    List<OutboxEvent> findTop50ByOrderByCreatedAtDesc();
    long countByStatus(String status);
}
