package com.ivis.boot.service;

import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.component.auth.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<LoginResponse> login(LoginRequest request) {
        // モックロジック：ユーザー名が "admin" でパスワードが "123456" であるかを確認
        // 実際には、データベースからユーザーを取得する必要があります
        return Mono.just(request)
            .filter(req -> "admin".equals(req.getUsername()))
            .filter(req -> "123456".equals(req.getPassword())) // モック用のプレーンテキストチェック
            .flatMap(req -> {
                String token = jwtUtil.generateToken(req.getUsername());
                // 有効期限（例：1時間）付きでトークンをRedisに保存
                String redisKey = "auth:token:" + req.getUsername();
                return redisTemplate.opsForValue().set(redisKey, token, Duration.ofHours(1))
                        .thenReturn(new LoginResponse(token, req.getUsername()));
            })
            .switchIfEmpty(Mono.error(new RuntimeException("Invalid username or password")));
    }
}
