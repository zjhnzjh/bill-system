package com.bill.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecoveryJobRepository extends JpaRepository<RecoveryJob, Long> {
    Optional<RecoveryJob> findByOrderIdAndJobType(String orderId, String jobType);
    List<RecoveryJob> findTop20ByStatusInAndNextRunAtBeforeOrderByNextRunAtAsc(Collection<String> statuses, Instant now);
    List<RecoveryJob> findTop50ByOrderByCreatedAtDesc();
}
