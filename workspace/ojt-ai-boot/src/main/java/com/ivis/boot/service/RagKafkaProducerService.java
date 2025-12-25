package com.ivis.boot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 生产者服务，发送消息到 RAG Worker
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagKafkaProducerService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Topic 名称（与 CONTRACT.md 保持一致）
    private static final String TOPIC_DOC_INGEST = "doc_ingest";
    private static final String TOPIC_SEARCH_REQUEST = "rag_search_request";
    private static final String TOPIC_ANSWER_REQUEST = "rag_answer_request";

    /**
     * 发送文档摄取事件到 doc_ingest topic
     * @param docId 文档ID
     * @param tenantId 租户ID
     * @param kbId 知识库ID
     * @param s3Path MinIO S3 路径
     * @param filename 文件名
     */
    public Mono<Void> sendDocIngestEvent(String docId, String tenantId, String kbId, 
                                          String s3Path, String filename) {
        Map<String, Object> event = new HashMap<>();
        event.put("doc_id", docId);
        event.put("tenant_id", tenantId);
        event.put("kb_id", kbId);
        event.put("s3_path", s3Path);
        event.put("filename", filename);

        return sendMessage(TOPIC_DOC_INGEST, docId, event);
    }

    /**
     * 发送搜索请求到 rag_search_request topic
     * @param requestId 请求ID
     * @param query 搜索查询
     * @param topK 返回结果数量
     * @param tenantId 租户ID
     * @param kbId 知识库ID
     */
    public Mono<Void> sendSearchRequest(String requestId, String query, Integer topK,
                                         String tenantId, String kbId) {
        Map<String, Object> request = new HashMap<>();
        request.put("request_id", requestId);
        request.put("trace_id", requestId);  // 使用 requestId 作为 trace_id
        request.put("query", query);
        request.put("topk", topK);  // 注意：Worker 期望的是 topk 而不是 top_k
        request.put("tenant_id", tenantId);  // 扁平结构
        request.put("kb_id", kbId);  // 扁平结构

        return sendMessage(TOPIC_SEARCH_REQUEST, requestId, request);
    }

    /**
     * 发送答案生成请求到 rag_answer_request topic
     * @param requestId 请求ID
     * @param question 问题
     * @param context 上下文（来自搜索结果）
     * @param tenantId 租户ID
     */
    public Mono<Void> sendAnswerRequest(String requestId, String question, 
                                         Object context, String tenantId) {
        Map<String, Object> request = new HashMap<>();
        request.put("request_id", requestId);
        request.put("question", question);
        request.put("context", context);
        
        Map<String, String> metadata = new HashMap<>();
        metadata.put("tenant", tenantId);
        request.put("metadata", metadata);

        return sendMessage(TOPIC_ANSWER_REQUEST, requestId, request);
    }

    /**
     * 通用发送消息方法
     */
    private Mono<Void> sendMessage(String topic, String key, Object payload) {
        return Mono.fromFuture(() -> {
            CompletableFuture<SendResult<String, Object>> future = 
                kafkaTemplate.send(topic, key, payload);
            
            return future.thenApply(result -> {
                log.info("Message sent to topic={}, key={}, partition={}, offset={}", 
                    topic, key, 
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
                return null;
            }).exceptionally(ex -> {
                log.error("Failed to send message to topic={}, key={}", topic, key, ex);
                throw new RuntimeException("Kafka send failed: " + ex.getMessage());
            });
        }).then();
    }
}
