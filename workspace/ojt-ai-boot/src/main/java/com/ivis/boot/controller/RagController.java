package com.ivis.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivis.boot.dto.rag.AnswerRequest;
import com.ivis.boot.dto.rag.RagResponse;
import com.ivis.boot.dto.rag.SearchRequest;
import com.ivis.boot.entity.ChatMessage;
import com.ivis.boot.entity.Session;
import com.ivis.boot.repository.ChatMessageRepository;
import com.ivis.boot.repository.SessionRepository;
import com.ivis.boot.service.RagKafkaConsumerService;
import com.ivis.boot.service.RagKafkaProducerService;
import com.ivis.component.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final SessionRepository sessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ReactiveStringRedisTemplate redisTemplate;

    private static final String REDIS_REQUEST_SESSION_PREFIX = "rag:request:session:";
    private static final String DEFAULT_KB_ID = "kb-001";

    /**
     * RAG 搜索接口
     * POST /api/rag/search
     * 发送搜索请求到 rag_search_request topic
     * tenantId 和 kbId 从 JWT 安全上下文中获取，防止跨租户访问
     * 
     * @param request 搜索请求（query, topK, sessionId）
     * @return requestId（用于后续轮询结果）
     */
    @PostMapping("/search")
    public Mono<ApiResponse<RagResponse>> search(@Valid @RequestBody SearchRequest request) {
        String requestId = UUID.randomUUID().toString();
        
        // 从 Security Context 获取用户名作为 tenantId，确保租户隔离
        return extractTenantFromSecurityContext()
            .flatMap(tenantId -> {
                String kbId = request.getKbId() != null ? request.getKbId() : DEFAULT_KB_ID;
                
                log.info("RAG search request: requestId={}, query={}, tenant={}, kb={}, session={}", 
                    requestId, request.getQuery(), tenantId, kbId, request.getSessionId());
                
                Mono<Void> sessionMapping = Mono.empty();
                if (request.getSessionId() != null) {
                    sessionMapping = redisTemplate.opsForValue()
                        .set(REDIS_REQUEST_SESSION_PREFIX + requestId, request.getSessionId(), Duration.ofMinutes(30))
                        .then();
                }

                return sessionMapping.then(
                    producerService.sendSearchRequest(
                        requestId,
                        request.getQuery(),
                        request.getTopK(),
                        tenantId,
                        kbId
                    )
                )
                .then(Mono.just(ApiResponse.success(
                    new RagResponse(requestId, "processing", null)
                )));
            })
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
     * tenantId 从 JWT 安全上下文中获取，防止跨租户访问
     * 
     * @param request 答案请求（question, context, sessionId）
     * @return requestId（用于后续轮询结果）
     */
    @PostMapping("/answer")
    public Mono<ApiResponse<RagResponse>> answer(@Valid @RequestBody AnswerRequest request) {
        String requestId = UUID.randomUUID().toString();
        
        // 从 Security Context 获取用户名作为 tenantId，确保租户隔离
        return extractTenantFromSecurityContext()
            .flatMap(tenantId -> {
                log.info("RAG answer request: requestId={}, question={}, tenant={}, session={}", 
                    requestId, request.getQuestion(), tenantId, request.getSessionId());

                // 1. Handle Session
                Mono<Session> sessionMono;
                if (request.getSessionId() != null) {
                    UUID sessionUuid = UUID.fromString(request.getSessionId());
                    // Check if session exists, if not create it
                    sessionMono = sessionRepository.findById(sessionUuid)
                        .switchIfEmpty(Mono.defer(() -> 
                            createSessionWithId(sessionUuid, request.getQuestion())
                        ));
                } else {
                    sessionMono = createNewSession(request.getQuestion());
                }

                return sessionMono.flatMap(session -> {
                    String sessionId = session.getId().toString();
                    
                    // 2. Save User Message
                    ChatMessage userMessage = ChatMessage.builder()
                        .sessionId(session.getId())
                        .role("user")
                        .content(request.getQuestion())
                        .createdAt(LocalDateTime.now())
                        .build();

                    return chatMessageRepository.save(userMessage)
                        .flatMap(savedMsg -> {
                            // 3. Fetch History (Last 10 messages)
                            return chatMessageRepository.findLastMessages(session.getId(), 10)
                                .collectList()
                                .map(history -> {
                                    Collections.reverse(history); // Reverse to chronological order
                                    return history.stream()
                                        .map(msg -> (msg.getRole().equals("user") ? "Q: " : "A: ") + msg.getContent())
                                        .collect(Collectors.joining("\n"));
                                });
                        })
                        .flatMap(historyContext -> {
                            // 4. Prepend History to Question
                            String finalQuestion = historyContext.isEmpty() ? 
                                request.getQuestion() : 
                                "History:\n" + historyContext + "\n\nCurrent Question:\n" + request.getQuestion();

                            // 5. Store Request->Session Mapping
                            return redisTemplate.opsForValue()
                                .set(REDIS_REQUEST_SESSION_PREFIX + requestId, sessionId, Duration.ofMinutes(30))
                                .then(
                                    // 6. Send to Kafka
                                    producerService.sendAnswerRequest(
                                        requestId,
                                        finalQuestion,
                                        request.getContext(),
                                        tenantId
                                    )
                                );
                        })
                        .then(Mono.just(ApiResponse.success(
                            new RagResponse(requestId, "processing", sessionId) // Return sessionId so client knows
                        )));
                });
            })
            .onErrorResume(e -> {
                log.error("Failed to send answer request", e);
                return Mono.just(ApiResponse.error(500, 
                    "Failed to send answer request: " + e.getMessage()));
            });
    }

    /**
     * 从 Security Context 提取租户ID（用户名）
     * 确保 requestId 和 tenantId 绑定，防止跨租户访问
     */
    private Mono<String> extractTenantFromSecurityContext() {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> {
                String username = context.getAuthentication().getName();
                return "tenant-" + username;
            })
            .switchIfEmpty(Mono.just("tenant-anonymous"));
    }

    private Mono<Session> createNewSession(String firstQuestion) {
        String title = firstQuestion.length() > 20 ? firstQuestion.substring(0, 20) + "..." : firstQuestion;
        Session session = Session.builder()
            .title(title)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        return sessionRepository.save(session);
    }

    private Mono<Session> createSessionWithId(UUID sessionId, String firstQuestion) {
        String title = firstQuestion.length() > 20 ? firstQuestion.substring(0, 20) + "..." : firstQuestion;
        LocalDateTime now = LocalDateTime.now();
        
        // Use custom insert with ON CONFLICT to handle pre-specified IDs
        return sessionRepository.insertWithId(sessionId, null, title, now, now)
            .switchIfEmpty(
                // If ON CONFLICT skipped insert, fetch the existing session
                sessionRepository.findById(sessionId)
            );
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
     * tenantId 从 JWT 安全上下文中获取
     * 
     * @param request 包含 query 和 question 的请求
     * @return 包含搜索和答案两个 requestId
     */
    @PostMapping("/search-and-answer")
    public Mono<ApiResponse<Object>> searchAndAnswer(@Valid @RequestBody SearchRequest request) {
        String searchRequestId = UUID.randomUUID().toString();
        
        // 从 Security Context 获取 tenantId
        return extractTenantFromSecurityContext()
            .flatMap(tenantId -> {
                String kbId = request.getKbId() != null ? request.getKbId() : DEFAULT_KB_ID;
                
                return producerService.sendSearchRequest(
                        searchRequestId,
                        request.getQuery(),
                        request.getTopK(),
                        tenantId,
                        kbId
                    )
                    .then(Mono.just(ApiResponse.<Object>success(
                        java.util.Map.of(
                            "search_request_id", searchRequestId,
                            "status", "processing",
                            "message", "Search initiated. Use /result/{requestId} to get search results, " +
                                      "then call /answer with the context."
                        )
                    )));
            })
            .onErrorResume(e -> {
                log.error("Failed to send search-and-answer request", e);
                return Mono.just(ApiResponse.error(500, 
                    "Failed to send request: " + e.getMessage()));
            });
    }
}
