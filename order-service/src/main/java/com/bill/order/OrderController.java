package com.bill.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    private final TraceEventRepository traces;
    private final RecoveryJobRepository jobs;
    private final RecoveryJobRunner jobRunner;

    public OrderController(OrderService service, TraceEventRepository traces,
                           RecoveryJobRepository jobs, RecoveryJobRunner jobRunner) {
        this.service = service;
        this.traces = traces;
        this.jobs = jobs;
        this.jobRunner = jobRunner;
    }

    public record CreateOrderRequest(@NotBlank String userId, @NotBlank String sku,
                                     @Min(1) int quantity, @DecimalMin("0.01") BigDecimal amount) {}
    public record PaymentCallback(@NotBlank String paymentId, @NotBlank String orderId, @NotBlank String status) {}

    @PostMapping
    public BillOrder create(@Valid @RequestBody CreateOrderRequest request,
                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                            @RequestHeader(value = "X-Trace-Id", required = false) String requestedTrace) {
        String traceId = resolveTraceId(requestedTrace);
        return service.create(idempotencyKey, request.userId(), request.sku(), request.quantity(), request.amount(), traceId);
    }

    @GetMapping
    public List<BillOrder> latest() { return service.latest(); }

    @GetMapping("/{orderId}")
    public BillOrder byId(@PathVariable String orderId) { return service.get(orderId); }

    @PostMapping("/{orderId}/pay")
    public BillOrder pay(@PathVariable String orderId,
                         @RequestParam(defaultValue = "false") boolean dropCallback,
                         @RequestHeader(value = "X-Trace-Id", required = false) String requestedTrace) {
        return service.pay(orderId, dropCallback, resolveTraceId(requestedTrace));
    }

    @PostMapping("/{orderId}/reconcile")
    public BillOrder reconcile(@PathVariable String orderId,
                               @RequestHeader(value = "X-Trace-Id", required = false) String requestedTrace) {
        return service.reconcile(orderId, resolveTraceId(requestedTrace));
    }

    @PostMapping("/{orderId}/cancel")
    public BillOrder cancel(@PathVariable String orderId) {
        return service.cancel(orderId);
    }

    @PostMapping("/{orderId}/refund")
    public BillOrder refund(@PathVariable String orderId) {
        return service.refund(orderId);
    }

    @PostMapping("/payment-callback")
    public BillOrder paymentCallback(@Valid @RequestBody PaymentCallback callback,
                                     @RequestHeader(value = "X-Trace-Id", required = false) String requestedTrace) {
        if (!"SUCCEEDED".equals(callback.status())) throw new IllegalArgumentException("Unsupported callback status");
        return service.markPaid(callback.orderId(), callback.paymentId(), resolveTraceId(requestedTrace));
    }

    @GetMapping("/{orderId}/trace")
    public List<TraceEvent> trace(@PathVariable String orderId) {
        return traces.findByTraceIdOrderByIdAsc(service.get(orderId).getTraceId());
    }

    @GetMapping("/jobs")
    public List<RecoveryJob> jobs() {
        return jobs.findTop50ByOrderByCreatedAtDesc();
    }

    @PostMapping("/jobs/{jobId}/retry")
    public RecoveryJob retryJob(@PathVariable Long jobId) {
        return jobRunner.retryNow(jobId);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, Object>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ORDER_REQUEST", "message", error.getMessage()));
    }

    private static String resolveTraceId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString().replace("-", "") : value;
    }
}
