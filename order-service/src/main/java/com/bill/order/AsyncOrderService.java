package com.bill.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AsyncOrderService {
    private static final int MAX_ATTEMPTS = 3;
    private final AsyncOrderRequestRepository requests;
    private final OutboxEventRepository outbox;
    private final KafkaDeliveryRepository deliveries;
    private final OrderService orders;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaFaults faults;
    private final LiveEventSocket events;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Object> acceptanceLocks = new ConcurrentHashMap<>();

    public AsyncOrderService(AsyncOrderRequestRepository requests, OutboxEventRepository outbox,
                             KafkaDeliveryRepository deliveries, OrderService orders,
                             KafkaTemplate<String, String> kafka, KafkaFaults faults,
                             LiveEventSocket events, ObjectMapper json) {
        this.requests = requests; this.outbox = outbox; this.deliveries = deliveries; this.orders = orders;
        this.kafka = kafka; this.faults = faults; this.events = events; this.json = json;
    }

    @Transactional
    public AsyncOrderRequest accept(String idempotencyKey, String userId, String sku, int quantity,
                                    BigDecimal amount, String traceId) {
        String fingerprint = fingerprint(userId + "|" + sku + "|" + quantity + "|" + amount.stripTrailingZeros().toPlainString());
        Object lock = acceptanceLocks.computeIfAbsent(idempotencyKey, ignored -> new Object());
        synchronized (lock) {
            try { return acceptTransaction(idempotencyKey, fingerprint, userId, sku, quantity, amount, traceId); }
            finally { acceptanceLocks.remove(idempotencyKey, lock); }
        }
    }

    protected AsyncOrderRequest acceptTransaction(String idempotencyKey, String fingerprint, String userId,
                                                   String sku, int quantity, BigDecimal amount, String traceId) {
        var existing = requests.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(fingerprint))
                throw new IllegalStateException("The idempotency key was already used with a different async request");
            return existing.get();
        }
        var request = requests.save(new AsyncOrderRequest(idempotencyKey, fingerprint, userId, sku, quantity, amount, traceId));
        var command = new OrderCommand(UUID.randomUUID().toString(), request.getId(), idempotencyKey,
                userId, sku, quantity, amount, traceId, 1);
        outbox.save(new OutboxEvent(request.getId(), KafkaTopics.COMMAND, request.getIdempotencyKey(), write(command)));
        events.publish("ASYNC_ORDER_ACCEPTED", "ORDER_REQUEST", request.getId(), traceId, "outbox=PENDING");
        return request;
    }

    @KafkaListener(id = "order-create-main", topics = KafkaTopics.COMMAND, groupId = KafkaTopics.GROUP, concurrency = "3")
    public void consumeMain(ConsumerRecord<String, String> record) { consume(record); }

    @KafkaListener(id = "order-create-retry", topics = KafkaTopics.RETRY, groupId = "order-create-retry-v1")
    public void consumeRetry(ConsumerRecord<String, String> record) {
        OrderCommand command = read(record.value());
        try {
            Thread.sleep(Math.min(2_000L, command.attempt() * 400L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        consume(record);
    }

    private void consume(ConsumerRecord<String, String> record) {
        OrderCommand command = read(record.value());
        AsyncOrderRequest request = requests.findById(command.requestId())
                .orElseThrow(() -> new IllegalArgumentException("Async request not found: " + command.requestId()));
        if (terminal(request.getStatus())) {
            request.duplicateReceived(record.topic(), record.partition(), record.offset());
            requests.save(request);
            deliveries.save(new KafkaDelivery(request.getId(), record.topic(), record.partition(), record.offset(),
                    command.attempt(), "DUPLICATE_IGNORED", "existing=" + request.getStatus()));
            events.publish("KAFKA_DUPLICATE_IGNORED", "ORDER_REQUEST", request.getId(), request.getTraceId(),
                    "received=" + request.getReceivedCount() + ", executed=" + request.getBusinessExecutions());
            return;
        }

        request.received(record.topic(), record.partition(), record.offset());
        requests.save(request);
        events.publish("ASYNC_ORDER_PROCESSING", "ORDER_REQUEST", request.getId(), request.getTraceId(),
                "topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset());

        try {
            if (faults.delayMs() > 0) Thread.sleep(faults.delayMs());
            if (faults.consumeFailure()) throw new IllegalStateException("Injected Kafka consumer technical failure");
            request.executed(); requests.save(request);
            BillOrder order = orders.create("kafka:" + request.getIdempotencyKey(), request.getUserId(), request.getSku(),
                    request.getQuantity(), request.getAmount(), request.getTraceId());
            if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                request.succeeded(order.getId());
                deliveries.save(new KafkaDelivery(request.getId(), record.topic(), record.partition(), record.offset(),
                        command.attempt(), "SUCCEEDED", "orderId=" + order.getId()));
                publishStatus(request, "SUCCEEDED");
            } else {
                request.rejected(order.getId(), order.getFailureReason() == null ? order.getStatus().name() : order.getFailureReason());
                deliveries.save(new KafkaDelivery(request.getId(), record.topic(), record.partition(), record.offset(),
                        command.attempt(), "BUSINESS_REJECTED", request.getLastError()));
                publishStatus(request, "REJECTED");
            }
            requests.save(request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); routeTechnicalFailure(request, command, record, "Consumer interrupted");
        } catch (RuntimeException error) {
            routeTechnicalFailure(request, command, record, safe(error));
        }
    }

    private void routeTechnicalFailure(AsyncOrderRequest request, OrderCommand command,
                                       ConsumerRecord<String, String> record, String error) {
        try {
            if (command.attempt() < MAX_ATTEMPTS) {
                OrderCommand retry = command.nextAttempt();
                kafka.send(KafkaTopics.RETRY, request.getIdempotencyKey(), write(retry)).get(3, TimeUnit.SECONDS);
                request.retrying(error); requests.save(request);
                deliveries.save(new KafkaDelivery(request.getId(), record.topic(), record.partition(), record.offset(),
                        command.attempt(), "RETRY_SCHEDULED", error));
                events.publish("KAFKA_RETRY_SCHEDULED", "ORDER_REQUEST", request.getId(), request.getTraceId(),
                        "nextAttempt=" + retry.attempt() + ", error=" + error);
            } else {
                kafka.send(KafkaTopics.DLQ, request.getIdempotencyKey(), write(command)).get(3, TimeUnit.SECONDS);
                request.deadLetter(error); requests.save(request);
                deliveries.save(new KafkaDelivery(request.getId(), record.topic(), record.partition(), record.offset(),
                        command.attempt(), "DEAD_LETTER", error));
                events.publish("KAFKA_DEAD_LETTER", "ORDER_REQUEST", request.getId(), request.getTraceId(), error);
            }
        } catch (Exception publishFailure) {
            request.deadLetter("Failure routing failed: " + safe(publishFailure)); requests.save(request);
        }
    }

    public AsyncOrderRequest duplicate(String requestId) {
        AsyncOrderRequest request = get(requestId);
        OrderCommand command = new OrderCommand(UUID.randomUUID().toString(), request.getId(), request.getIdempotencyKey(),
                request.getUserId(), request.getSku(), request.getQuantity(), request.getAmount(), request.getTraceId(), 1);
        send(KafkaTopics.COMMAND, request, command);
        return request;
    }

    public AsyncOrderRequest replay(String requestId) {
        AsyncOrderRequest request = get(requestId);
        if (request.getStatus() != AsyncOrderStatus.DEAD_LETTER)
            throw new IllegalStateException("Only a dead-letter request can be replayed");
        request.requeue(); requests.save(request);
        OrderCommand command = new OrderCommand(UUID.randomUUID().toString(), request.getId(), request.getIdempotencyKey(),
                request.getUserId(), request.getSku(), request.getQuantity(), request.getAmount(), request.getTraceId(), 1);
        send(KafkaTopics.COMMAND, request, command);
        events.publish("DLQ_MANUAL_REPLAY", "ORDER_REQUEST", request.getId(), request.getTraceId(), "attempt=1");
        return request;
    }

    private void send(String topic, AsyncOrderRequest request, OrderCommand command) {
        try { kafka.send(topic, request.getIdempotencyKey(), write(command)).get(3, TimeUnit.SECONDS); }
        catch (Exception error) { throw new IllegalStateException("Kafka publish failed: " + safe(error), error); }
    }

    private void publishStatus(AsyncOrderRequest request, String status) {
        String payload = "{\"requestId\":\"" + request.getId() + "\",\"orderId\":\"" + request.getOrderId() + "\",\"status\":\"" + status + "\"}";
        kafka.send(KafkaTopics.STATUS, request.getId(), payload);
        events.publish("ASYNC_ORDER_" + status, "ORDER_REQUEST", request.getId(), request.getTraceId(),
                "orderId=" + request.getOrderId());
    }

    public AsyncOrderRequest get(String id) { return requests.findById(id).orElseThrow(() -> new IllegalArgumentException("Async request not found: " + id)); }
    public List<AsyncOrderRequest> latest() { return requests.findTop50ByOrderByAcceptedAtDesc(); }
    public List<OutboxEvent> outbox() { return outbox.findTop50ByOrderByCreatedAtDesc(); }
    public List<KafkaDelivery> deliveries() { return deliveries.findTop100ByOrderByOccurredAtDesc(); }
    public List<KafkaDelivery> deliveries(String requestId) { return deliveries.findByRequestIdOrderByOccurredAtAsc(requestId); }

    private String write(OrderCommand value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot serialize order command", error); }
    }
    private OrderCommand read(String value) {
        try { return json.readValue(value, OrderCommand.class); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Invalid order command", error); }
    }
    private static boolean terminal(AsyncOrderStatus status) {
        return status == AsyncOrderStatus.SUCCEEDED || status == AsyncOrderStatus.REJECTED || status == AsyncOrderStatus.DEAD_LETTER;
    }
    private static String safe(Throwable error) {
        Throwable value = error.getCause() == null ? error : error.getCause();
        String message = value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
