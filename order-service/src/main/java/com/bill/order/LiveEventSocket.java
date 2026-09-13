package com.bill.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LiveEventSocket extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final ObjectMapper json;

    public LiveEventSocket(ObjectMapper json) { this.json = json; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        publish("STATE_REFRESH", "SYSTEM", "bill-system", null, "reason=connection-established");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Scheduled(fixedRate = 5000)
    public void publishHeartbeat() {
        publish("HEARTBEAT", "SYSTEM", "bill-system", null, "connections=" + sessions.size());
    }

    public void publish(String eventType, String aggregateType, String aggregateId, String traceId, String detail) {
        long next = sequence.incrementAndGet();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("sequence", next);
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("occurredAt", Instant.now());
        envelope.put("traceId", traceId);
        envelope.put("payloadVersion", 1);
        envelope.put("detail", detail);
        try {
            String payload = json.writeValueAsString(envelope);
            sessions.removeIf(session -> !send(session, payload));
        } catch (JsonProcessingException ignored) {
        }
    }

    public int connectionCount() { return sessions.size(); }
    public long currentSequence() { return sequence.get(); }

    private boolean send(WebSocketSession session, String payload) {
        if (!session.isOpen()) return false;
        try {
            synchronized (session) { session.sendMessage(new TextMessage(payload)); }
            return true;
        } catch (IOException error) {
            return false;
        }
    }
}
