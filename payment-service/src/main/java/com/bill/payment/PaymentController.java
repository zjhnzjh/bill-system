package com.bill.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentRepository payments;
    private final RestClient restClient;
    private final PaymentFaults faults;

    public PaymentController(PaymentRepository payments, RestClient.Builder builder, PaymentFaults faults) {
        this.payments = payments;
        this.restClient = builder.build();
        this.faults = faults;
    }

    public record CreatePaymentRequest(
            @NotBlank String orderId,
            @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String callbackUrl) {}

    @PostMapping
    @Transactional
    public Payment create(@Valid @RequestBody CreatePaymentRequest request) {
        faults.beforeCall();
        return payments.findByOrderId(request.orderId()).map(existing -> {
            if (existing.getAmount().compareTo(request.amount()) != 0) {
                throw new IllegalStateException("Payment amount conflicts with the existing order payment");
            }
            return existing;
        }).orElseGet(() -> payments.save(new Payment(request.orderId(), request.amount(), request.callbackUrl())));
    }

    @PostMapping("/{paymentId}/succeed")
    @Transactional
    public Payment succeed(@PathVariable String paymentId, @RequestParam(defaultValue = "false") boolean dropCallback) {
        var payment = payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        if (!"REFUNDED".equals(payment.getStatus())) payment.succeed();
        if (!dropCallback) {
            try {
                restClient.post().uri(payment.getCallbackUrl()).body(Map.of(
                        "paymentId", payment.getId(),
                        "orderId", payment.getOrderId(),
                        "status", payment.getStatus()))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException ignored) {
                // The payment fact remains successful; order reconciliation must repair a lost callback.
            }
        }
        return payment;
    }

    @PostMapping("/{paymentId}/refund")
    @Transactional
    public Payment refund(@PathVariable String paymentId) {
        var payment = payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        if (!"SUCCEEDED".equals(payment.getStatus()) && !"REFUNDED".equals(payment.getStatus())) {
            throw new IllegalStateException("Only a successful payment can be refunded");
        }
        payment.refund();
        return payment;
    }

    @GetMapping("/orders/{orderId}")
    public Payment byOrder(@PathVariable String orderId) {
        faults.beforeQuery();
        return payments.findByOrderId(orderId).orElseThrow(() -> new IllegalArgumentException("Payment not found"));
    }

    public record FaultRequest(int failNextQueries, int failNextCalls) {}

    @PostMapping("/faults")
    public Map<String, Integer> configureFaults(@RequestBody FaultRequest request) {
        return faults.configure(request.failNextQueries(), request.failNextCalls());
    }

    @GetMapping("/faults")
    public Map<String, Integer> faultState() {
        return faults.state();
    }

    @GetMapping("/{paymentId}")
    public Payment byId(@PathVariable String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("Payment not found"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, Object>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PAYMENT_REQUEST", "message", error.getMessage()));
    }
}
