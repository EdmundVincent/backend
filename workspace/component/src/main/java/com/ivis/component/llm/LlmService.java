package com.ivis.component.llm;

public interface LlmService {
    String chat(String prompt);
    String chat(String systemPrompt, String userPrompt);
}
