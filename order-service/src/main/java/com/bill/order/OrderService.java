package com.bill.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final TraceEventRepository traces;
    private final DownstreamClients downstream;
    private final RecoveryJobRepository jobs;
    private final String publicOrderUrl;
    private final TransactionTemplate transactions;
    private final LiveEventSocket liveEvents;
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    public OrderService(OrderRepository orders, TraceEventRepository traces, DownstreamClients downstream,
                        RecoveryJobRepository jobs,
                        TransactionTemplate transactions, LiveEventSocket liveEvents,
                        @Value("${services.public-order-url:http://order-service:8785}") String publicOrderUrl) {
        this.orders = orders;
        this.traces = traces;
        this.downstream = downstream;
        this.jobs = jobs;
        this.transactions = transactions;
        this.liveEvents = liveEvents;
        this.publicOrderUrl = publicOrderUrl;
    }

    public BillOrder create(String idempotencyKey, String userId, String sku, int quantity,
                            BigDecimal amount, String traceId) {
        String fingerprint = fingerprint(userId + "|" + sku + "|" + quantity + "|" + amount.stripTrailingZeros().toPlainString());
        Object lock = idempotencyLocks.computeIfAbsent(idempotencyKey, ignored -> new Object());
        synchronized (lock) {
            try {
                BillOrder result = transactions.execute(ignored -> createInTransaction(
                        idempotencyKey, fingerprint, userId, sku, quantity, amount, traceId));
                liveEvents.publish("ORDER_STATE_CHANGED", "ORDER", result.getId(), result.getTraceId(), "status=" + result.getStatus());
                return result;
            } catch (DataIntegrityViolationException race) {
                var winner = orders.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
                ensureSameFingerprint(winner, fingerprint);
                return winner;
            } finally {
                idempotencyLocks.remove(idempotencyKey, lock);
            }
        }
    }

    private BillOrder createInTransaction(String idempotencyKey, String fingerprint, String userId, String sku,
                                          int quantity, BigDecimal amount, String traceId) {
        var existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            ensureSameFingerprint(existing.get(), fingerprint);
            record(traceId, "idempotency.replay", "REUSED", 0, existing.get().getId());
            return existing.get();
        }

        var order = orders.saveAndFlush(new BillOrder(idempotencyKey, fingerprint, userId, sku, quantity, amount, traceId));
        long inventoryStarted = System.nanoTime();
        try {
            downstream.reserve(order.getId(), sku, quantity, traceId);
            record(traceId, "inventory.reserve", "OK", elapsed(inventoryStarted), order.getId());
            order.transition(OrderStatus.STOCK_RESERVED);
        } catch (RuntimeException error) {
            record(traceId, "inventory.reserve", "FAILED", elapsed(inventoryStarted), safe(error));
            order.fail(safe(error), OrderStatus.FAILED);
            return order;
        }

        long paymentStarted = System.nanoTime();
        try {
            Map<String, Object> payment = downstream.createPayment(order.getId(), amount,
                    publicOrderUrl + "/api/orders/payment-callback", traceId);
            order.attachPayment(String.valueOf(payment.get("id")));
            order.transition(OrderStatus.PENDING_PAYMENT);
            record(traceId, "payment.create", "OK", elapsed(paymentStarted), order.getPaymentId());
        } catch (RuntimeException error) {
            record(traceId, "payment.create", "FAILED", elapsed(paymentStarted), safe(error));
            try {
                downstream.release(order.getId(), traceId);
                record(traceId, "payment.create.compensate", "STOCK_RELEASED", 0, order.getId());
            } catch (RuntimeException compensationError) {
                record(traceId, "payment.create.compensate", "FAILED", 0, safe(compensationError));
            }
            order.fail(safe(error), OrderStatus.MANUAL_REVIEW);
        }
        return order;
    }

    private static void ensureSameFingerprint(BillOrder existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new IllegalStateException("The idempotency key was already used with a different request");
        }
    }

    @Transactional
    public BillOrder markPaid(String orderId, String paymentId, String traceId) {
        var order = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        String correlatedTrace = order.getTraceId();
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.FULFILLED) {
            record(correlatedTrace, "payment.callback", "DUPLICATE_IGNORED", 0, paymentId);
            return order;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !paymentId.equals(order.getPaymentId())) {
            throw new IllegalStateException("Payment callback does not match the current order state");
        }
        order.transition(OrderStatus.PAID);
        record(correlatedTrace, "payment.callback", "OK", 0, paymentId);
        liveEvents.publish("ORDER_STATE_CHANGED", "ORDER", order.getId(), correlatedTrace, "status=PAID");
        return order;
    }

    @Transactional
    public BillOrder reconcile(String orderId, String traceId) {
        var order = get(orderId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) return order;
        String correlatedTrace = order.getTraceId();
        long started = System.nanoTime();
        var payment = downstream.paymentByOrder(orderId, correlatedTrace);
        if ("SUCCEEDED".equals(String.valueOf(payment.get("status")))) {
            record(correlatedTrace, "payment.reconcile", "RECOVERED", elapsed(started), String.valueOf(payment.get("id")));
            return markPaid(orderId, String.valueOf(payment.get("id")), correlatedTrace);
        }
        record(correlatedTrace, "payment.reconcile", "NO_CHANGE", elapsed(started), String.valueOf(payment.get("status")));
        return order;
    }

    public BillOrder pay(String orderId, boolean dropCallback, String traceId) {
        var order = get(orderId);
        String correlatedTrace = order.getTraceId();
        long started = System.nanoTime();
        downstream.succeedPayment(order.getPaymentId(), dropCallback, correlatedTrace);
        record(correlatedTrace, "payment.succeed", dropCallback ? "CALLBACK_DROPPED" : "OK", elapsed(started), order.getPaymentId());
        if (dropCallback) {
            jobs.findByOrderIdAndJobType(orderId, "PAYMENT_RECONCILE")
                    .orElseGet(() -> jobs.save(new RecoveryJob(orderId, "PAYMENT_RECONCILE", correlatedTrace)));
        }
        liveEvents.publish("PAYMENT_FACT_CHANGED", "ORDER", orderId, correlatedTrace, "callbackDropped=" + dropCallback);
        return get(orderId);
    }

    public BillOrder get(String id) {
        return orders.findById(id).orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
    }

    @Transactional
    public BillOrder cancel(String orderId) {
        var order = get(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) return order;
        if (order.getStatus() != OrderStatus.STOCK_RESERVED && order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Only an unpaid reserved order can be cancelled");
        }
        long started = System.nanoTime();
        downstream.release(orderId, order.getTraceId());
        order.transition(OrderStatus.CANCELLED);
        record(order.getTraceId(), "order.cancel.inventory.release", "OK", elapsed(started), orderId);
        liveEvents.publish("ORDER_STATE_CHANGED", "ORDER", order.getId(), order.getTraceId(), "status=CANCELLED");
        return order;
    }

    @Transactional
    public BillOrder refund(String orderId) {
        var order = get(orderId);
        if (order.getStatus() == OrderStatus.REFUNDED) return order;
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Only a paid order can be refunded");
        }
        long started = System.nanoTime();
        downstream.refundPayment(order.getPaymentId(), order.getTraceId());
        downstream.release(orderId, order.getTraceId());
        order.transition(OrderStatus.REFUNDED);
        record(order.getTraceId(), "order.refund.compensation", "OK", elapsed(started), order.getPaymentId());
        liveEvents.publish("ORDER_STATE_CHANGED", "ORDER", order.getId(), order.getTraceId(), "status=REFUNDED");
        return order;
    }

    public List<BillOrder> latest() {
        return orders.findAll().stream().sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt())).limit(50).toList();
    }

    private void record(String traceId, String operation, String outcome, long duration, String detail) {
        traces.save(new TraceEvent(traceId, "order-service", operation, outcome, duration, detail));
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static String safe(Throwable error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() > 480 ? value.substring(0, 480) : value;
    }
    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
