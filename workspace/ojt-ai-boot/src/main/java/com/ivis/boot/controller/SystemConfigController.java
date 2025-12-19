package com.ivis.boot.controller;

import com.ivis.component.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
public class SystemConfigController {

    @GetMapping("/config")
    public Mono<ApiResponse<Map<String, Object>>> getSystemConfig() {
        // Mocking system configuration
        Map<String, Object> config = new HashMap<>();
        config.put("systemName", "OJT-AI Knowledge Base");
        config.put("version", "1.0.0");
        config.put("allowSignup", true);
        config.put("maintenanceMode", false);
        
        return Mono.just(ApiResponse.success(config));
    }
}
