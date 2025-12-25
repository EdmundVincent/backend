package com.ivis.boot.service;

import com.ivis.boot.entity.ChatMessage;
import com.ivis.boot.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka 消费者服务，监听 RAG Worker 返回的结果
 * 修复早期 ACK 问题：确保 Redis 写入成功后再提交 offset
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagKafkaConsumerService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WebSocketSessionManager sessionManager;
    private final ChatMessageRepository chatMessageRepository;

    // Redis Key 前缀
    private static final String REDIS_RESULT_PREFIX = "rag:result:";
    private static final String REDIS_REQUEST_SESSION_PREFIX = "rag:request:session:";
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * 监听搜索结果 topic
     * 确保 Redis 写入成功后再 ACK
     */
    @KafkaListener(topics = "rag_search_result", groupId = "backend-api")
    public void handleSearchResult(@Payload String message,
                                    @Header(KafkaHeaders.RECEIVED_KEY) String key,
                                    Acknowledgment acknowledgment) {
        try {
            log.info("Received search result, key={}", key);
            JsonNode result = objectMapper.readTree(message);
            
            // 安全获取 request_id，防止 NPE
            JsonNode requestIdNode = result.get("request_id");
            if (requestIdNode == null || requestIdNode.isNull()) {
                log.error("Received search result without request_id, acknowledging and skipping");
                acknowledgment.acknowledge();
                return;
            }
            String requestId = requestIdNode.asText();
            
            // 确保 Redis 写入成功后再 ACK
            storeResultBlocking(requestId, message)
                .doOnSuccess(success -> {
                    // WebSocket 推送是尽力而为，不影响 ACK
                    pushToWebSocket(requestId, message);
                    acknowledgment.acknowledge();
                    log.info("Search result processed and acknowledged: requestId={}", requestId);
                })
                .doOnError(error -> {
                    log.error("Failed to store search result in Redis, not acknowledging: requestId={}", requestId, error);
                    // 不调用 acknowledge()，消息将被重新消费
                })
                .block(); // 在 Kafka 监听器中必须阻塞等待结果
            
        } catch (Exception e) {
            log.error("Failed to handle search result, not acknowledging", e);
            // 不提交 offset，让消息重新消费
        }
    }

    /**
     * 监听答案结果 topic
     * 确保 Redis 写入成功后再 ACK
     */
    @KafkaListener(topics = "rag_answer_result", groupId = "backend-api")
    public void handleAnswerResult(@Payload String message,
                                    @Header(KafkaHeaders.RECEIVED_KEY) String key,
                                    Acknowledgment acknowledgment) {
        try {
            log.info("Received answer result, key={}", key);
            JsonNode result = objectMapper.readTree(message);
            
            // 安全获取 request_id，防止 NPE
            JsonNode requestIdNode = result.get("request_id");
            if (requestIdNode == null || requestIdNode.isNull()) {
                log.error("Received answer result without request_id, acknowledging and skipping");
                acknowledgment.acknowledge();
                return;
            }
            String requestId = requestIdNode.asText();
            
            // 确保 Redis 写入成功后再 ACK
            storeResultBlocking(requestId, message)
                .then(saveAiAnswerToDbBlocking(requestId, result))
                .doOnSuccess(success -> {
                    pushToWebSocket(requestId, message);
                    acknowledgment.acknowledge();
                    log.info("Answer result processed and acknowledged: requestId={}", requestId);
                })
                .doOnError(error -> {
                    log.error("Failed to process answer result, not acknowledging: requestId={}", requestId, error);
                })
                .block();
            
        } catch (Exception e) {
            log.error("Failed to handle answer result, not acknowledging", e);
        }
    }

    /**
     * 保存 AI 答案到数据库（返回 Mono，支持链式调用）
     */
    private Mono<Void> saveAiAnswerToDbBlocking(String requestId, JsonNode result) {
        String sessionKey = REDIS_REQUEST_SESSION_PREFIX + requestId;
        return redisTemplate.opsForValue().get(sessionKey)
            .flatMap(sessionId -> {
                String answer = result.has("answer") ? result.get("answer").asText() : "";
                if (answer.isEmpty()) {
                    return Mono.empty();
                }

                ChatMessage aiMessage = ChatMessage.builder()
                    .sessionId(UUID.fromString(sessionId))
                    .role("assistant")
                    .content(answer)
                    .createdAt(LocalDateTime.now())
                    .build();
                
                return chatMessageRepository.save(aiMessage)
                    .doOnSuccess(saved -> log.info("AI answer saved to DB for session: {}", sessionId))
                    .doOnError(error -> log.error("Failed to save AI answer to DB for session: {}", sessionId, error))
                    .then();
            })
            .onErrorResume(e -> {
                // DB 保存失败不应阻止消息确认，只记录警告
                log.warn("Failed to save AI answer to DB, continuing: {}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 监听失败消息 topic
     * 确保 Redis 写入成功后再 ACK
     */
    @KafkaListener(topics = "rag_failed", groupId = "backend-api")
    public void handleFailure(@Payload String message,
                               @Header(KafkaHeaders.RECEIVED_KEY) String key,
                               Acknowledgment acknowledgment) {
        try {
            log.warn("Received failure message, key={}, message={}", key, message);
            JsonNode failure = objectMapper.readTree(message);
            
            // 安全获取 request_id，防止 NPE
            JsonNode requestIdNode = failure.get("request_id");
            if (requestIdNode == null || requestIdNode.isNull()) {
                log.error("Received failure message without request_id, acknowledging and skipping");
                acknowledgment.acknowledge();
                return;
            }
            String requestId = requestIdNode.asText();
            
            // Worker 发送的格式是 error: {code, message}
            JsonNode errorNode = failure.get("error");
            String errorCode = errorNode != null && errorNode.has("code") ? errorNode.get("code").asText() : "UNKNOWN_ERROR";
            String errorMessage = errorNode != null && errorNode.has("message") ? errorNode.get("message").asText() : "Unknown error";
            
            log.error("RAG processing failed - requestId={}, code={}, message={}", 
                requestId, errorCode, errorMessage);
            
            // 存储失败信息
            Map<String, Object> errorResult = Map.of(
                "status", "failed",
                "error_code", errorCode,
                "error_message", errorMessage,
                "type", failure.has("type") ? failure.get("type").asText() : "unknown"
            );
            String errorJson = objectMapper.writeValueAsString(errorResult);
            
            // 确保 Redis 写入成功后再 ACK
            storeResultBlocking(requestId, errorJson)
                .doOnSuccess(success -> {
                    pushToWebSocket(requestId, errorJson);
                    acknowledgment.acknowledge();
                    log.info("Failure result processed and acknowledged: requestId={}", requestId);
                })
                .doOnError(error -> {
                    log.error("Failed to store failure result in Redis, not acknowledging: requestId={}", requestId, error);
                })
                .block();
            
        } catch (Exception e) {
            log.error("Failed to handle failure message, not acknowledging", e);
        }
    }

    /**
     * 存储结果到 Redis（阻塞版本，返回 Mono 用于链式调用）
     * 包含重试机制
     */
    private Mono<Boolean> storeResultBlocking(String requestId, String resultJson) {
        String redisKey = REDIS_RESULT_PREFIX + requestId;
        return redisTemplate.opsForValue()
            .set(redisKey, resultJson, RESULT_TTL)
            .retry(MAX_RETRY_ATTEMPTS)
            .doOnSuccess(success -> log.info("Result stored in Redis: {}", redisKey))
            .doOnError(error -> log.error("Failed to store result in Redis after {} retries: {}", MAX_RETRY_ATTEMPTS, redisKey, error));
    }

    /**
     * 推送结果到 WebSocket（尽力而为，失败不影响主流程）
     */
    private void pushToWebSocket(String requestId, String message) {
        String sessionKey = REDIS_REQUEST_SESSION_PREFIX + requestId;
        redisTemplate.opsForValue().get(sessionKey)
            .flatMap(sessionId -> {
                log.info("Pushing result to session: {}", sessionId);
                return sessionManager.sendMessage(sessionId, message);
            })
            .subscribe(
                null,
                error -> log.warn("Failed to push to WebSocket (best-effort): {}", error.getMessage()),
                () -> log.debug("WebSocket push completed for request {}", requestId)
            );
    }

    /**
     * 从 Redis 获取结果（给 Controller 调用）
     */
    public Mono<String> getResult(String requestId) {
        String redisKey = REDIS_RESULT_PREFIX + requestId;
        return redisTemplate.opsForValue().get(redisKey);
    }
}
