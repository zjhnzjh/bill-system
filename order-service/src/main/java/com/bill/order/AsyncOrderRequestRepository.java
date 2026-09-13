package com.bill.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AsyncOrderRequestRepository extends JpaRepository<AsyncOrderRequest, String> {
    Optional<AsyncOrderRequest> findByIdempotencyKey(String idempotencyKey);
    List<AsyncOrderRequest> findTop50ByOrderByAcceptedAtDesc();
    long countByStatus(AsyncOrderStatus status);
}
