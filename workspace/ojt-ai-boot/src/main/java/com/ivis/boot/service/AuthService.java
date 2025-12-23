package com.ivis.boot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.boot.dto.auth.RegisterRequest;
import com.ivis.boot.dto.auth.UserCacheInfo;
import com.ivis.boot.entity.User;
import com.ivis.boot.repository.UserRepository;
import com.ivis.component.auth.JwtUtil;
import com.ivis.component.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 認証サービス
 * データベース認証とRedisキャッシュを統合
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    /**
     * ログイン処理
     * 1. データベースからユーザーを取得
     * 2. パスワードを検証（BCrypt）
     * 3. JWT を生成
     * 4. Token とユーザー情報を Redis にキャッシュ
     */
    public Mono<LoginResponse> login(LoginRequest request) {
        return userRepository.findByUsernameAndEnabled(request.getUsername())
            .switchIfEmpty(Mono.error(new BusinessException(401, "Invalid username or password")))
            .filter(user -> passwordEncoder.matches(request.getPassword(), user.getPassword()))
            .switchIfEmpty(Mono.error(new BusinessException(401, "Invalid username or password")))
            .flatMap(user -> {
                String token = jwtUtil.generateToken(user.getUsername());
                
                // 1. Token をRedisに保存
                String tokenKey = "auth:token:" + user.getUsername();
                Mono<Boolean> saveToken = redisTemplate.opsForValue()
                        .set(tokenKey, token, Duration.ofHours(24));
                
                // 2. ユーザー情報をRedisにキャッシュ
                UserCacheInfo userInfo = new UserCacheInfo();
                userInfo.setUsername(user.getUsername());
                userInfo.setUserId(user.getId());
                userInfo.setRoles(Arrays.asList(user.getRolesArray()));
                userInfo.setPermissions(Arrays.asList("READ", "WRITE"));
                userInfo.setLoginTime(LocalDateTime.now());
                userInfo.setLastAccessTime(LocalDateTime.now());
                
                String userInfoKey = "user:info:" + user.getUsername();
                Mono<Boolean> saveUserInfo = Mono.fromCallable(() -> objectMapper.writeValueAsString(userInfo))
                        .flatMap(json -> redisTemplate.opsForValue()
                                .set(userInfoKey, json, Duration.ofHours(24)));
                
                log.info("User logged in: {}", user.getUsername());
                
                return Mono.zip(saveToken, saveUserInfo)
                        .thenReturn(new LoginResponse(token, user.getUsername()));
            });
    }

    /**
     * ユーザー登録
     */
    public Mono<User> register(RegisterRequest request) {
        return userRepository.existsByUsername(request.getUsername())
            .flatMap(exists -> {
                if (exists) {
                    return Mono.error(new BusinessException(400, "Username already exists"));
                }
                return userRepository.existsByEmail(request.getEmail());
            })
            .flatMap(emailExists -> {
                if (emailExists) {
                    return Mono.error(new BusinessException(400, "Email already exists"));
                }
                
                User user = User.builder()
                        .username(request.getUsername())
                        .password(passwordEncoder.encode(request.getPassword()))
                        .email(request.getEmail())
                        .enabled(true)
                        .roles("USER")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                
                log.info("Registering new user: {}", request.getUsername());
                return userRepository.save(user);
            });
    }

    /**
     * ログアウト処理
     */
    public Mono<Void> logout(String token) {
        return Mono.fromCallable(() -> jwtUtil.extractUsername(token))
                .flatMap(username -> {
                    if (username != null) {
                        String tokenKey = "auth:token:" + username;
                        String userInfoKey = "user:info:" + username;
                        
                        log.info("User logged out: {}", username);
                        
                        return Mono.zip(
                                redisTemplate.opsForValue().delete(tokenKey),
                                redisTemplate.opsForValue().delete(userInfoKey)
                        ).then();
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("Logout failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Redisからユーザー情報を取得
     */
    public Mono<UserCacheInfo> getUserInfo(String username) {
        if (username == null) {
            return Mono.empty();
        }
        
        String userInfoKey = "user:info:" + username;
        return redisTemplate.opsForValue().get(userInfoKey)
                .flatMap(json -> {
                    try {
                        UserCacheInfo userInfo = objectMapper.readValue(json, UserCacheInfo.class);
                        return Mono.just(userInfo);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to parse user info from cache", e);
                        return Mono.empty();
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // キャッシュミス時はDBから取得してキャッシュ
                    return userRepository.findByUsername(username)
                            .flatMap(user -> {
                                UserCacheInfo userInfo = new UserCacheInfo();
                                userInfo.setUsername(user.getUsername());
                                userInfo.setUserId(user.getId());
                                userInfo.setRoles(Arrays.asList(user.getRolesArray()));
                                userInfo.setPermissions(Arrays.asList("READ", "WRITE"));
                                userInfo.setLastAccessTime(LocalDateTime.now());
                                
                                // キャッシュに保存
                                return Mono.fromCallable(() -> objectMapper.writeValueAsString(userInfo))
                                        .flatMap(jsonStr -> redisTemplate.opsForValue()
                                                .set(userInfoKey, jsonStr, Duration.ofHours(1)))
                                        .thenReturn(userInfo);
                            });
                }));
    }
}
