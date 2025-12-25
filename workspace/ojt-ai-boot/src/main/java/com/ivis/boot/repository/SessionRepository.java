package com.ivis.boot.repository;

import com.ivis.boot.entity.Session;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface SessionRepository extends ReactiveCrudRepository<Session, UUID> {
    Flux<Session> findByUserId(Long userId);
    
    @Query("INSERT INTO sessions (id, user_id, title, created_at, updated_at) " +
           "VALUES (:id, :userId, :title, :createdAt, :updatedAt) " +
           "ON CONFLICT (id) DO NOTHING " +
           "RETURNING *")
    Mono<Session> insertWithId(UUID id, Long userId, String title, LocalDateTime createdAt, LocalDateTime updatedAt);
}
