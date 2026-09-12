package com.bill.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TraceEventRepository extends JpaRepository<TraceEvent, Long> {
    List<TraceEvent> findByTraceIdOrderByIdAsc(String traceId);
}

