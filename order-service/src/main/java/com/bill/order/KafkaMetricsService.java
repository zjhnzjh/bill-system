package com.bill.order;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class KafkaMetricsService {
    private final AsyncOrderRequestRepository requests;
    private final OutboxEventRepository outbox;
    private final KafkaDeliveryRepository deliveries;
    private final LiveEventSocket events;
    private final String bootstrapServers;

    public KafkaMetricsService(AsyncOrderRequestRepository requests, OutboxEventRepository outbox,
                               KafkaDeliveryRepository deliveries, LiveEventSocket events,
                               @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.requests = requests; this.outbox = outbox; this.deliveries = deliveries;
        this.events = events; this.bootstrapServers = bootstrapServers;
    }

    public Map<String, Object> snapshot(boolean paused, long consumerDelayMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("broker", "DOWN");
        result.put("topic", KafkaTopics.COMMAND);
        result.put("consumerGroup", KafkaTopics.GROUP);
        result.put("partitions", 3);
        result.put("logEndOffset", 0L);
        result.put("committedOffset", 0L);
        result.put("lag", 0L);
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.listTopics().names().get(2, TimeUnit.SECONDS);
            result.put("broker", "UP");
            Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
            for (int partition = 0; partition < 3; partition++) latest.put(new TopicPartition(KafkaTopics.COMMAND, partition), OffsetSpec.latest());
            var ends = admin.listOffsets(latest).all().get(2, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed;
            try { committed = admin.listConsumerGroupOffsets(KafkaTopics.GROUP).partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS); }
            catch (Exception absentGroup) { committed = Map.of(); }
            long end = 0; long position = 0;
            List<Map<String, Object>> partitionRows = new ArrayList<>();
            for (int partition = 0; partition < 3; partition++) {
                TopicPartition tp = new TopicPartition(KafkaTopics.COMMAND, partition);
                long partitionEnd = ends.get(tp).offset();
                long partitionCommitted = committed.getOrDefault(tp, new OffsetAndMetadata(0)).offset();
                end += partitionEnd; position += partitionCommitted;
                partitionRows.add(Map.of("partition", partition, "endOffset", partitionEnd,
                        "committedOffset", partitionCommitted, "lag", Math.max(0, partitionEnd - partitionCommitted)));
            }
            result.put("logEndOffset", end); result.put("committedOffset", position); result.put("lag", Math.max(0, end - position));
            result.put("partitionDetails", partitionRows);
        } catch (Exception error) {
            result.put("error", safe(error));
        }
        result.put("accepted", requests.count());
        result.put("queued", requests.countByStatus(AsyncOrderStatus.ACCEPTED) + requests.countByStatus(AsyncOrderStatus.QUEUED));
        result.put("processing", requests.countByStatus(AsyncOrderStatus.PROCESSING) + requests.countByStatus(AsyncOrderStatus.RETRYING));
        result.put("succeeded", requests.countByStatus(AsyncOrderStatus.SUCCEEDED));
        result.put("rejected", requests.countByStatus(AsyncOrderStatus.REJECTED));
        result.put("deadLetter", requests.countByStatus(AsyncOrderStatus.DEAD_LETTER));
        result.put("outboxPending", outbox.countByStatus("PENDING"));
        result.put("outboxPublished", outbox.countByStatus("PUBLISHED"));
        result.put("deliveries", deliveries.count());
        result.put("duplicates", deliveries.countByOutcome("DUPLICATE_IGNORED"));
        result.put("consumerPaused", paused);
        result.put("consumerDelayMs", consumerDelayMs);
        result.put("webSocketConnections", events.connectionCount());
        result.put("lastEventSequence", events.currentSequence());
        result.put("oldestQueueAgeMs", oldestQueueAge());
        result.put("measuredAt", Instant.now());
        return result;
    }

    private long oldestQueueAge() {
        return requests.findTop50ByOrderByAcceptedAtDesc().stream()
                .filter(value -> value.getStatus() == AsyncOrderStatus.ACCEPTED || value.getStatus() == AsyncOrderStatus.QUEUED || value.getStatus() == AsyncOrderStatus.PROCESSING)
                .mapToLong(value -> Duration.between(value.getAcceptedAt(), Instant.now()).toMillis()).max().orElse(0);
    }

    private static String safe(Throwable error) {
        Throwable value = error.getCause() == null ? error : error.getCause();
        String message = value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
