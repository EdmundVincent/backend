package com.ivis.boot.controller;

import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.boot.service.AuthService;
import com.ivis.component.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Mono<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
        return authService.login(request)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(401, e.getMessage())));
    }
}
