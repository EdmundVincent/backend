package com.ivis.boot.dto.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerRequest {
    
    @NotBlank(message = "Question cannot be empty")
    @Size(max = 5000, message = "Question cannot exceed 5000 characters")
    private String question;
    
    @NotNull(message = "Context cannot be null")
    private List<Map<String, Object>> context;
    
    // tenantId 和 kbId 不再从客户端接收，将从 JWT 注入
    private String tenantId;
    private String kbId;
    private String sessionId;
}
