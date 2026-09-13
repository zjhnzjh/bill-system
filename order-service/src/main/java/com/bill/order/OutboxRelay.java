package com.bill.order;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelay {
    private final OutboxEventRepository outbox;
    private final AsyncOrderRequestRepository requests;
    private final KafkaTemplate<String, String> kafka;
    private final LiveEventSocket events;

    public OutboxRelay(OutboxEventRepository outbox, AsyncOrderRequestRepository requests,
                       KafkaTemplate<String, String> kafka, LiveEventSocket events) {
        this.outbox = outbox; this.requests = requests; this.kafka = kafka; this.events = events;
    }

    @Scheduled(fixedDelayString = "${kafka-demo.relay-delay-ms:250}")
    public void publishPending() {
        for (OutboxEvent event : outbox.findTop25ByStatusOrderByCreatedAtAsc("PENDING")) {
            try {
                var result = kafka.send(event.getTopic(), event.getMessageKey(), event.getPayload()).get(3, TimeUnit.SECONDS);
                event.published(result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                outbox.save(event);
                requests.findById(event.getAggregateId()).ifPresent(request -> {
                    request.published(event.getTopic(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                    requests.save(request);
                    events.publish("ASYNC_ORDER_QUEUED", "ORDER_REQUEST", request.getId(), request.getTraceId(),
                            "topic=" + event.getTopic() + ", partition=" + result.getRecordMetadata().partition() + ", offset=" + result.getRecordMetadata().offset());
                });
            } catch (Exception error) {
                event.failed(safe(error)); outbox.save(event);
            }
        }
    }

    private static String safe(Throwable error) {
        Throwable value = error.getCause() == null ? error : error.getCause();
        String message = value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
