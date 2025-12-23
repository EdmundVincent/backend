package com.ivis.boot.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse {
    private String requestId;
    private String status; // "processing", "completed", "failed"
    private Object result;  // SearchResult or AnswerResult
}
