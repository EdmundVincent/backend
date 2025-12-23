package com.ivis.boot.service;

import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.boot.entity.User;
import com.ivis.boot.repository.UserRepository;
import com.ivis.component.auth.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AuthService 単体テスト
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("admin");
        testUser.setPassword("$2a$10$encoded_password");
        testUser.setEmail("admin@example.com");
        testUser.setEnabled(true);
        testUser.setRoles("ROLE_ADMIN,ROLE_USER");
    }

    @Test
    @DisplayName("正しい認証情報でログインが成功する")
    void login_WithValidCredentials_ShouldReturnLoginResponse() {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("123456");

        when(userRepository.findByUsernameAndEnabled("admin")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("123456", testUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateToken("admin")).thenReturn("test-jwt-token");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));

        // When
        Mono<LoginResponse> result = authService.login(request);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(response -> 
                    response.getToken().equals("test-jwt-token") &&
                    response.getUsername().equals("admin")
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("無効なパスワードでログインが失敗する")
    void login_WithInvalidPassword_ShouldReturnError() {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrongpassword");

        when(userRepository.findByUsernameAndEnabled("admin")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("wrongpassword", testUser.getPassword())).thenReturn(false);

        // When
        Mono<LoginResponse> result = authService.login(request);

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> e.getMessage().contains("Invalid"))
                .verify();
    }

    @Test
    @DisplayName("存在しないユーザーでログインが失敗する")
    void login_WithNonExistentUser_ShouldReturnError() {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("nonexistent");
        request.setPassword("password");

        when(userRepository.findByUsernameAndEnabled("nonexistent")).thenReturn(Mono.empty());

        // When
        Mono<LoginResponse> result = authService.login(request);

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(e -> e.getMessage().contains("Invalid"))
                .verify();
    }
}
