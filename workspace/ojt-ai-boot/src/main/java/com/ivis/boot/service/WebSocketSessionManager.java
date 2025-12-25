package com.ivis.boot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WebSocketSessionManager {

    // Map<SessionId, WebSocketSession>
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void registerSession(String sessionId, WebSocketSession session) {
        sessions.put(sessionId, session);
        log.info("WebSocket session registered: {}", sessionId);
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("WebSocket session removed: {}", sessionId);
    }

    public Mono<Void> sendMessage(String sessionId, String message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            WebSocketMessage webSocketMessage = session.textMessage(message);
            return session.send(Mono.just(webSocketMessage))
                    .doOnError(e -> log.error("Failed to send message to session {}", sessionId, e));
        } else {
            log.warn("Session {} not found or closed", sessionId);
            return Mono.empty();
        }
    }
}
