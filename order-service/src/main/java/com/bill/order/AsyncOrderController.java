package com.bill.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
@RequestMapping("/api/async-orders")
public class AsyncOrderController {
    private final AsyncOrderService service;
    private final KafkaMetricsService metrics;
    private final KafkaFaults faults;
    private final KafkaListenerEndpointRegistry registry;

    public AsyncOrderController(AsyncOrderService service, KafkaMetricsService metrics,
                                KafkaFaults faults, KafkaListenerEndpointRegistry registry) {
        this.service = service; this.metrics = metrics; this.faults = faults; this.registry = registry;
    }

    public record CreateAsyncOrderRequest(@NotBlank String userId, @NotBlank String sku,
                                          @Min(1) int quantity, @DecimalMin("0.01") BigDecimal amount) {}

    @PostMapping
    public ResponseEntity<AsyncOrderRequest> accept(@Valid @RequestBody CreateAsyncOrderRequest request,
                                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ResponseEntity.accepted().body(service.accept(idempotencyKey, request.userId(), request.sku(),
                request.quantity(), request.amount(), traceId == null || traceId.isBlank() ? UUID.randomUUID().toString().replace("-", "") : traceId));
    }

    @GetMapping public List<AsyncOrderRequest> latest() { return service.latest(); }
    @GetMapping("/{requestId}") public AsyncOrderRequest byId(@PathVariable String requestId) { return service.get(requestId); }
    @GetMapping("/{requestId}/deliveries") public List<KafkaDelivery> deliveries(@PathVariable String requestId) { return service.deliveries(requestId); }
    @GetMapping("/outbox/all") public List<OutboxEvent> outbox() { return service.outbox(); }
    @GetMapping("/deliveries/all") public List<KafkaDelivery> deliveries() { return service.deliveries(); }
    @GetMapping("/metrics") public Map<String, Object> metrics() { return metrics.snapshot(paused(), faults.delayMs()); }
    @GetMapping("/faults") public Map<String, Object> faults() { return faults.state(); }

    @PostMapping("/{requestId}/duplicate") public AsyncOrderRequest duplicate(@PathVariable String requestId) { return service.duplicate(requestId); }
    @PostMapping("/{requestId}/replay") public AsyncOrderRequest replay(@PathVariable String requestId) { return service.replay(requestId); }

    @PostMapping("/faults")
    public Map<String, Object> configureFaults(@RequestParam(defaultValue = "0") int failNext,
                                               @RequestParam(defaultValue = "0") long delayMs) {
        faults.configure(failNext, delayMs); return faults.state();
    }

    @PostMapping("/consumer/pause") public Map<String, Object> pause() { setPaused(true); return faults.state(); }
    @PostMapping("/consumer/resume") public Map<String, Object> resume() { setPaused(false); return faults.state(); }

    private void setPaused(boolean pause) {
        for (String id : List.of("order-create-main", "order-create-retry")) {
            var container = registry.getListenerContainer(id);
            if (container != null) { if (pause) container.pause(); else container.resume(); }
        }
        faults.setPaused(pause);
    }
    private boolean paused() { return faults.isPaused(); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, Object>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ASYNC_REQUEST", "message", error.getMessage()));
    }
}
