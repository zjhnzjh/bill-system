package com.bill.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService service;
    private final InventoryRepository inventory;
    private final InventoryFaults faults;

    public InventoryController(InventoryService service, InventoryRepository inventory, InventoryFaults faults) {
        this.service = service;
        this.inventory = inventory;
        this.faults = faults;
    }

    public record ReserveRequest(@NotBlank String orderId, @NotBlank String sku, @Min(1) int quantity) {}

    @PostMapping("/reservations")
    public InventoryReservation reserve(@Valid @RequestBody ReserveRequest request) {
        faults.beforeReserve();
        return service.reserve(request.orderId(), request.sku(), request.quantity());
    }

    public record FaultRequest(@Min(0) int failNext, @Min(0) long delayNextMs) {}

    @PostMapping("/faults")
    public Map<String, Object> configureFaults(@Valid @RequestBody FaultRequest request) {
        return faults.configure(request.failNext(), request.delayNextMs());
    }

    @GetMapping("/faults")
    public Map<String, Object> faultState() {
        return faults.state();
    }

    @PostMapping("/reservations/{orderId}/release")
    public InventoryReservation release(@PathVariable String orderId) {
        return service.release(orderId);
    }

    @GetMapping("/items/{sku}")
    public InventoryItem item(@PathVariable String sku) {
        return inventory.findById(sku).orElseThrow(() -> new IllegalArgumentException("Unknown SKU: " + sku));
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Map<String, Object>> insufficient(InsufficientStockException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "INSUFFICIENT_STOCK", "message", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, Object>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INVENTORY_REQUEST", "message", error.getMessage()));
    }
}
