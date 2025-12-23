package com.ivis.boot.service;

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

import java.time.Duration;
import java.util.Map;

/**
 * Kafka 消费者服务，监听 RAG Worker 返回的结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagKafkaConsumerService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // Redis Key 前缀
    private static final String REDIS_RESULT_PREFIX = "rag:result:";
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);

    /**
     * 监听搜索结果 topic
     */
    @KafkaListener(topics = "rag_search_result", groupId = "backend-api")
    public void handleSearchResult(@Payload String message,
                                    @Header(KafkaHeaders.RECEIVED_KEY) String key,
                                    Acknowledgment acknowledgment) {
        try {
            log.info("Received search result, key={}", key);
            JsonNode result = objectMapper.readTree(message);
            String requestId = result.get("request_id").asText();
            
            // 存入 Redis，前端可轮询获取
            storeResult(requestId, message);
            
            // 手动提交 offset
            acknowledgment.acknowledge();
            
        } catch (Exception e) {
            log.error("Failed to handle search result", e);
            // 不提交 offset，让消息重新消费
        }
    }

    /**
     * 监听答案结果 topic
     */
    @KafkaListener(topics = "rag_answer_result", groupId = "backend-api")
    public void handleAnswerResult(@Payload String message,
                                    @Header(KafkaHeaders.RECEIVED_KEY) String key,
                                    Acknowledgment acknowledgment) {
        try {
            log.info("Received answer result, key={}", key);
            JsonNode result = objectMapper.readTree(message);
            String requestId = result.get("request_id").asText();
            
            storeResult(requestId, message);
            acknowledgment.acknowledge();
            
        } catch (Exception e) {
            log.error("Failed to handle answer result", e);
        }
    }

    /**
     * 监听失败消息 topic
     */
    @KafkaListener(topics = "rag_failed", groupId = "backend-api")
    public void handleFailure(@Payload String message,
                               @Header(KafkaHeaders.RECEIVED_KEY) String key,
                               Acknowledgment acknowledgment) {
        try {
            log.warn("Received failure message, key={}", key);
            JsonNode failure = objectMapper.readTree(message);
            String requestId = failure.get("request_id").asText();
            String errorCode = failure.get("error_code").asText();
            String errorMessage = failure.get("error_message").asText();
            
            log.error("RAG processing failed - requestId={}, code={}, message={}", 
                requestId, errorCode, errorMessage);
            
            // 存储失败信息
            Map<String, Object> errorResult = Map.of(
                "status", "failed",
                "error_code", errorCode,
                "error_message", errorMessage,
                "stage", failure.get("stage").asText()
            );
            storeResult(requestId, objectMapper.writeValueAsString(errorResult));
            
            acknowledgment.acknowledge();
            
        } catch (Exception e) {
            log.error("Failed to handle failure message", e);
        }
    }

    /**
     * 存储结果到 Redis（供前端轮询）
     */
    private void storeResult(String requestId, String resultJson) {
        String redisKey = REDIS_RESULT_PREFIX + requestId;
        redisTemplate.opsForValue()
            .set(redisKey, resultJson, RESULT_TTL)
            .subscribe(
                success -> log.info("Result stored in Redis: {}", redisKey),
                error -> log.error("Failed to store result in Redis", error)
            );
    }

    /**
     * 从 Redis 获取结果（给 Controller 调用）
     */
    public reactor.core.publisher.Mono<String> getResult(String requestId) {
        String redisKey = REDIS_RESULT_PREFIX + requestId;
        return redisTemplate.opsForValue().get(redisKey);
    }
}
