package com.ivis.boot.socket;

import com.ivis.boot.service.WebSocketSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler {

    private final WebSocketSessionManager sessionManager;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String sessionId = getSessionId(session);
        if (sessionId == null) {
            return session.close();
        }

        sessionManager.registerSession(sessionId, session);

        return session.receive()
                .doOnNext(message -> {
                    // Handle incoming messages if needed (e.g. ping/pong or client-side chat)
                    // For now we just log them
                    log.debug("Received message from {}: {}", sessionId, message.getPayloadAsText());
                })
                .doOnComplete(() -> sessionManager.removeSession(sessionId))
                .doOnError(e -> sessionManager.removeSession(sessionId))
                .then();
    }

    private String getSessionId(WebSocketSession session) {
        // Extract sessionId from query param: ws://localhost:8080/ws/chat?sessionId=...
        URI uri = session.getHandshakeInfo().getUri();
        String query = uri.getQuery();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] idx = pair.split("=");
                if (idx.length == 2 && "sessionId".equals(idx[0])) {
                    return idx[1];
                }
            }
        }
        return null;
    }
}
