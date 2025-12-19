package com.ivis.boot.controller;

import com.ivis.component.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public Mono<ApiResponse<String>> hello() {
        return Mono.just(ApiResponse.success("Hello from OJT-AI WebFlux!"));
    }
}
