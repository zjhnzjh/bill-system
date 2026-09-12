package com.bill.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class RecoveryJobRunner {
    private final RecoveryJobRepository jobs;
    private final OrderService orders;

    public RecoveryJobRunner(RecoveryJobRepository jobs, OrderService orders) {
        this.jobs = jobs;
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "${jobs.poll-delay-ms:2000}")
    public void runDueJobs() {
        var due = jobs.findTop20ByStatusInAndNextRunAtBeforeOrderByNextRunAtAsc(
                List.of("PENDING", "RETRYING"), Instant.now());
        due.forEach(this::execute);
    }

    @Transactional
    public RecoveryJob retryNow(Long id) {
        var job = jobs.findById(id).orElseThrow(() -> new IllegalArgumentException("Job not found: " + id));
        job.retryNow();
        return job;
    }

    private void execute(RecoveryJob job) {
        try {
            if (!"PAYMENT_RECONCILE".equals(job.getJobType())) {
                throw new IllegalStateException("Unknown job type: " + job.getJobType());
            }
            orders.reconcile(job.getOrderId(), job.getTraceId());
            if (orders.get(job.getOrderId()).getStatus() != OrderStatus.PAID) {
                throw new IllegalStateException("Payment has not reached a successful state");
            }
            job.succeeded();
            jobs.save(job);
        } catch (RuntimeException error) {
            job.failed(error);
            jobs.save(job);
        }
    }
}
