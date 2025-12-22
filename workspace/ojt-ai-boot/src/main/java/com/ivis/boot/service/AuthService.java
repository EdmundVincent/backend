package com.ivis.boot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.boot.dto.auth.UserCacheInfo;
import com.ivis.component.auth.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Mono<LoginResponse> login(LoginRequest request) {
        // モックロジック：ユーザー名が "admin" でパスワードが "123456" であるかを確認
        // 実際には、データベースからユーザーを取得する必要があります
        return Mono.just(request)
            .filter(req -> "admin".equals(req.getUsername()))
            .filter(req -> "123456".equals(req.getPassword())) // モック用のプレーンテキストチェック
            .flatMap(req -> {
                String token = jwtUtil.generateToken(req.getUsername());
                String username = req.getUsername();
                
                // 1. Token をRedisに保存
                String tokenKey = "auth:token:" + username;
                Mono<Boolean> saveToken = redisTemplate.opsForValue()
                        .set(tokenKey, token, Duration.ofHours(1));
                
                // 2. 【重要】ユーザー情報もRedisにキャッシュ（DBクエリを削減）
                UserCacheInfo userInfo = new UserCacheInfo();
                userInfo.setUsername(username);
                userInfo.setUserId(1001L); // Mock: 実際はDBから取得
                userInfo.setRoles(Arrays.asList("ADMIN", "USER"));
                userInfo.setPermissions(Arrays.asList("READ_USER", "WRITE_USER", "DELETE_USER"));
                userInfo.setLoginTime(LocalDateTime.now());
                userInfo.setLastAccessTime(LocalDateTime.now());
                
                String userInfoKey = "user:info:" + username;
                Mono<Boolean> saveUserInfo = Mono.fromCallable(() -> objectMapper.writeValueAsString(userInfo))
                        .flatMap(json -> redisTemplate.opsForValue()
                                .set(userInfoKey, json, Duration.ofHours(1)));
                
                // 両方の保存が完了してからレスポンスを返す
                return Mono.zip(saveToken, saveUserInfo)
                        .thenReturn(new LoginResponse(token, username));
            })
            .switchIfEmpty(Mono.error(new RuntimeException("Invalid username or password")));
    }

    /**
     * ログアウト処理：Redis から Token とユーザー情報を削除
     * リアクティブ方式で実装し、スレッドをブロックしない
     */
    public Mono<Void> logout(String token) {
        return Mono.fromCallable(() -> jwtUtil.extractUsername(token))
                .flatMap(username -> {
                    if (username != null) {
                        String tokenKey = "auth:token:" + username;
                        String userInfoKey = "user:info:" + username;
                        
                        // Token とユーザー情報の両方を削除
                        return Mono.zip(
                                redisTemplate.opsForValue().delete(tokenKey),
                                redisTemplate.opsForValue().delete(userInfoKey)
                        ).then();
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    // Token パース失敗時は静かに無視
                    return Mono.empty();
                });
    }

    /**
     * Redisからユーザー情報を取得
     * キャッシュヒット時はDBアクセス不要
     */
    public Mono<UserCacheInfo> getUserInfo(String username) {
        String userInfoKey = "user:info:" + username;
        return redisTemplate.opsForValue().get(userInfoKey)
                .flatMap(json -> {
                    try {
                        UserCacheInfo userInfo = objectMapper.readValue(json, UserCacheInfo.class);
                        return Mono.just(userInfo);
                    } catch (JsonProcessingException e) {
                        return Mono.empty();
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // キャッシュミス時は、実際のプロジェクトではDBから取得
                    // 現在はMockデータを返す
                    return Mono.empty();
                }));
    }
}
