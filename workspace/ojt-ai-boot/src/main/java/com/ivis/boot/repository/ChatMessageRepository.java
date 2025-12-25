package com.ivis.boot.repository;

import com.ivis.boot.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Repository
public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, UUID> {
    
    // Fetch messages for a session, ordered by time
    Flux<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    // Fetch last N messages for context (we might need to reverse them in service)
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    Flux<ChatMessage> findLastMessages(UUID sessionId, int limit);
}
