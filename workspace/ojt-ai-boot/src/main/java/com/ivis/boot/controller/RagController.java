package com.ivis.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivis.boot.dto.rag.AnswerRequest;
import com.ivis.boot.dto.rag.RagResponse;
import com.ivis.boot.dto.rag.SearchRequest;
import com.ivis.boot.service.RagKafkaConsumerService;
import com.ivis.boot.service.RagKafkaProducerService;
import com.ivis.component.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * RAG 搜索和答案生成 Controller
 * API2: RAG 搜索
 * API3: RAG 答案生成
 * API0: 结果轮询
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagKafkaProducerService producerService;
    private final RagKafkaConsumerService consumerService;
    private final ObjectMapper objectMapper;

    /**
     * RAG 搜索接口
     * POST /api/rag/search
     * 发送搜索请求到 rag_search_request topic
     * 
     * @param request 搜索请求（query, topK, tenantId, kbId）
     * @return requestId（用于后续轮询结果）
     */
    @PostMapping("/search")
    public Mono<ApiResponse<RagResponse>> search(@RequestBody SearchRequest request) {
        String requestId = UUID.randomUUID().toString();
        
        log.info("RAG search request: requestId={}, query={}, tenant={}, kb={}", 
            requestId, request.getQuery(), request.getTenantId(), request.getKbId());
        
        return producerService.sendSearchRequest(
                requestId,
                request.getQuery(),
                request.getTopK(),
                request.getTenantId(),
                request.getKbId()
            )
            .then(Mono.just(ApiResponse.success(
                new RagResponse(requestId, "processing", null)
            )))
            .onErrorResume(e -> {
                log.error("Failed to send search request", e);
                return Mono.just(ApiResponse.error(500, 
                    "Failed to send search request: " + e.getMessage()));
            });
    }

    /**
     * RAG 答案生成接口
     * POST /api/rag/answer
     * 发送答案请求到 rag_answer_request topic
     * 
     * @param request 答案请求（question, context, tenantId）
     * @return requestId（用于后续轮询结果）
     */
    @PostMapping("/answer")
    public Mono<ApiResponse<RagResponse>> answer(@RequestBody AnswerRequest request) {
        String requestId = UUID.randomUUID().toString();
        
        log.info("RAG answer request: requestId={}, question={}, tenant={}", 
            requestId, request.getQuestion(), request.getTenantId());
        
        return producerService.sendAnswerRequest(
                requestId,
                request.getQuestion(),
                request.getContext(),
                request.getTenantId()
            )
            .then(Mono.just(ApiResponse.success(
                new RagResponse(requestId, "processing", null)
            )))
            .onErrorResume(e -> {
                log.error("Failed to send answer request", e);
                return Mono.just(ApiResponse.error(500, 
                    "Failed to send answer request: " + e.getMessage()));
            });
    }

    /**
     * 结果轮询接口
     * GET /api/rag/result/{requestId}
     * 从 Redis 获取 RAG Worker 返回的结果
     * 
     * @param requestId 请求ID
     * @return 搜索结果或答案结果，如果还在处理中则返回 404
     */
    @GetMapping("/result/{requestId}")
    public Mono<ApiResponse<?>> getResult(@PathVariable String requestId) {
        return consumerService.getResult(requestId)
            .flatMap(resultJson -> {
                try {
                    // 解析 JSON 结果
                    JsonNode result = objectMapper.readTree(resultJson);
                    
                    // 判断是否为失败消息
                    if (result.has("status") && "failed".equals(result.get("status").asText())) {
                        return Mono.just(ApiResponse.error(
                            500,
                            result.get("error_message").asText()
                        ));
                    }
                    
                    // 返回成功结果
                    return Mono.just(ApiResponse.success(result));
                    
                } catch (Exception e) {
                    log.error("Failed to parse result JSON", e);
                    return Mono.just(ApiResponse.error(500, 
                        "Failed to parse result: " + e.getMessage()));
                }
            })
            .switchIfEmpty(Mono.just(ApiResponse.error(404, 
                "Result not ready yet, please try again later")));
    }

    /**
     * 组合接口：搜索后直接生成答案
     * POST /api/rag/search-and-answer
     * 
     * @param request 包含 query 和 question 的请求
     * @return 包含搜索和答案两个 requestId
     */
    @PostMapping("/search-and-answer")
    public Mono<ApiResponse<Object>> searchAndAnswer(@RequestBody SearchRequest request) {
        String searchRequestId = UUID.randomUUID().toString();
        
        // 先发送搜索请求
        return producerService.sendSearchRequest(
                searchRequestId,
                request.getQuery(),
                request.getTopK(),
                request.getTenantId(),
                request.getKbId()
            )
            .then(Mono.just(ApiResponse.<Object>success(
                java.util.Map.of(
                    "search_request_id", searchRequestId,
                    "status", "processing",
                    "message", "Search initiated. Use /result/{requestId} to get search results, " +
                              "then call /answer with the context."
                )
            )))
            .onErrorResume(e -> {
                log.error("Failed to send search-and-answer request", e);
                return Mono.just(ApiResponse.error(500, 
                    "Failed to send request: " + e.getMessage()));
            });
    }
}
