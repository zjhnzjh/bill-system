package com.bill.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KafkaDeliveryRepository extends JpaRepository<KafkaDelivery, Long> {
    List<KafkaDelivery> findTop100ByOrderByOccurredAtDesc();
    List<KafkaDelivery> findByRequestIdOrderByOccurredAtAsc(String requestId);
    long countByOutcome(String outcome);
}
