package com.ivis.boot.dto.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    
    @NotBlank(message = "Query cannot be empty")
    @Size(max = 2000, message = "Query cannot exceed 2000 characters")
    private String query;
    
    @Min(value = 1, message = "topK must be at least 1")
    @Max(value = 100, message = "topK cannot exceed 100")
    private Integer topK = 5;
    
    // tenantId 和 kbId 不再从客户端接收，将从 JWT 注入
    // 保留字段用于内部传递，但不接受客户端输入
    private String tenantId;
    private String kbId;
    private String sessionId;
}
