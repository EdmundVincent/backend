package com.ivis.component.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Azure OpenAI を使用した LLM サービス実装
 */
@Slf4j
@Service
public class AzureOpenAiLlmService implements LlmService {

    private final WebClient webClient;
    private final String deployment;
    private final String apiVersion;

    public AzureOpenAiLlmService(
            @Value("${ai.openai.endpoint:}") String endpoint,
            @Value("${ai.openai.api-key:}") String apiKey,
            @Value("${ai.openai.deployment:gpt-4}") String deployment,
            @Value("${ai.openai.api-version:2024-05-01-preview}") String apiVersion) {
        
        this.deployment = deployment;
        this.apiVersion = apiVersion;
        
        if (endpoint != null && !endpoint.isEmpty() && apiKey != null && !apiKey.isEmpty()) {
            this.webClient = WebClient.builder()
                    .baseUrl(endpoint)
                    .defaultHeader("api-key", apiKey)
                    .defaultHeader("Content-Type", "application/json")
                    .build();
            log.info("Azure OpenAI service initialized with endpoint: {}", endpoint);
        } else {
            this.webClient = null;
            log.warn("Azure OpenAI not configured. LLM features will be disabled.");
        }
    }

    @Override
    public String chat(String prompt) {
        return chat("You are a helpful assistant.", prompt);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (webClient == null) {
            log.warn("Azure OpenAI not configured, returning mock response");
            return "LLM service is not configured. Please set Azure OpenAI credentials.";
        }

        try {
            return chatAsync(systemPrompt, userPrompt).block();
        } catch (Exception e) {
            log.error("Failed to call Azure OpenAI", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 非同期チャット（リアクティブ）
     */
    public Mono<String> chatAsync(String systemPrompt, String userPrompt) {
        if (webClient == null) {
            return Mono.just("LLM service is not configured.");
        }

        Map<String, Object> requestBody = Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", 2000,
                "temperature", 0.7
        );

        String uri = String.format("/openai/deployments/%s/chat/completions?api-version=%s",
                deployment, apiVersion);

        return webClient.post()
                .uri(uri)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        return (String) message.get("content");
                    }
                    return "No response from LLM";
                })
                .onErrorResume(e -> {
                    log.error("Azure OpenAI call failed", e);
                    return Mono.just("Error: " + e.getMessage());
                });
    }
}
