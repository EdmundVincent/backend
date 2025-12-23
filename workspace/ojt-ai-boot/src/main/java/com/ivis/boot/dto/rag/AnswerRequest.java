package com.ivis.boot.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerRequest {
    private String question;
    private List<Map<String, Object>> context;
    private String tenantId;
    private String kbId;
}
