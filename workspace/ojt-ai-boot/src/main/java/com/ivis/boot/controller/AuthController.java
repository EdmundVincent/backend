package com.ivis.boot.controller;

import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.boot.dto.auth.UserCacheInfo;
import com.ivis.boot.service.AuthService;
import com.ivis.component.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor

public class AuthController {

    private final AuthService authService;
    private final com.ivis.component.auth.JwtUtil jwtUtil;

    @PostMapping("/login")
    public Mono<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
        return authService.login(request)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(401, e.getMessage())));
    }

    @PostMapping("/logout")
    public Mono<ApiResponse<Void>> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return authService.logout(token)
                    .thenReturn(ApiResponse.success(null));
        }
        return Mono.just(ApiResponse.success(null));
    }

    /**
     * ユーザー情報取得API
     * Redis キャッシュから取得（DBアクセス不要）
     */
    @GetMapping("/userinfo")
    public Mono<ApiResponse<UserCacheInfo>> getUserInfo(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            // Token から username を抽出し、Redis からユーザー情報を取得
            return authService.getUserInfo(extractUsernameFromToken(token))
                    .map(ApiResponse::success)
                    .switchIfEmpty(Mono.just(ApiResponse.error(404, "User not found in cache")));
        }
        return Mono.just(ApiResponse.error(401, "Invalid token"));
    }

    /**
     * Token から username を抽出
     * JwtUtil を使用して実際にパースする
     */
    private String extractUsernameFromToken(String token) {
        try {
            return jwtUtil.extractUsername(token);
        } catch (Exception e) {
            // Token が無効な場合は null を返す
            return null;
        }
    }
}
