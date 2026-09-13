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
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService service;
    private final InventoryRepository inventory;
    private final InventoryFaults faults;
    private final ReservationRepository reservations;
    private final InventoryAuditRepository audits;

    public InventoryController(InventoryService service, InventoryRepository inventory, InventoryFaults faults,
                               ReservationRepository reservations, InventoryAuditRepository audits) {
        this.service = service;
        this.inventory = inventory;
        this.faults = faults;
        this.reservations = reservations;
        this.audits = audits;
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

    @GetMapping("/items")
    public List<InventoryItem> items() { return inventory.findAll(); }

    @GetMapping("/reservations")
    public List<InventoryReservation> reservations() {
        return reservations.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/admin/audits")
    public List<InventoryAudit> audits() { return audits.findTop50ByOrderByOccurredAtDesc(); }

    public record StockChange(@Min(0) int available, @NotBlank String operator) {}

    @PostMapping("/admin/items/{sku}/stock")
    @org.springframework.transaction.annotation.Transactional
    public InventoryItem setStock(@PathVariable String sku, @Valid @RequestBody StockChange request) {
        var item = inventory.findBySkuForUpdate(sku)
                .orElseThrow(() -> new IllegalArgumentException("Unknown SKU: " + sku));
        int before = item.getAvailable();
        item.setAvailable(request.available());
        audits.save(new InventoryAudit(sku, "SET_AVAILABLE", request.operator(),
                "available " + before + " -> " + request.available() + ", reserved=" + item.getReserved()));
        return item;
    }

    public record RestoreItem(@Min(0) int available, @NotBlank String operator) {}

    @PostMapping("/admin/items/{sku}/restore")
    @org.springframework.transaction.annotation.Transactional
    public InventoryItem restore(@PathVariable String sku, @Valid @RequestBody RestoreItem request) {
        var item = inventory.findById(sku).orElseGet(() -> new InventoryItem(sku, request.available()));
        item.setAvailable(request.available());
        var saved = inventory.save(item);
        audits.save(new InventoryAudit(sku, "RESTORE_ITEM", request.operator(),
                "available=" + request.available() + ", reserved=" + saved.getReserved()));
        return saved;
    }

    public record AdminAction(@NotBlank String operator) {}

    @PostMapping("/admin/items/{sku}/delete")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> delete(@PathVariable String sku, @Valid @RequestBody AdminAction request) {
        var item = inventory.findById(sku).orElseThrow(() -> new IllegalArgumentException("Unknown SKU: " + sku));
        if (item.getReserved() > 0 || reservations.existsBySkuAndStatus(sku, "RESERVED")) {
            throw new IllegalStateException("Cannot delete an item with active reservations");
        }
        String before = "available=" + item.getAvailable() + ", reserved=" + item.getReserved();
        inventory.delete(item);
        audits.save(new InventoryAudit(sku, "DELETE_ITEM", request.operator(), before));
        return Map.of("sku", sku, "deleted", true);
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
