package com.bill.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, Long> {
    List<PaymentAudit> findTop50ByOrderByOccurredAtDesc();
}
