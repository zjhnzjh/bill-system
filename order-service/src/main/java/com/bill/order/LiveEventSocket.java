package com.bill.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LiveEventSocket extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Scheduled(fixedRate = 1000)
    public void publishRefreshTick() {
        String payload = "{\"type\":\"STATE_REFRESH\",\"at\":\"" + Instant.now() + "\"}";
        sessions.removeIf(session -> !send(session, payload));
    }

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
