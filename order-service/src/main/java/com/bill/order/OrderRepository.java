package com.bill.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<BillOrder, String> {
    Optional<BillOrder> findByIdempotencyKey(String idempotencyKey);
}

