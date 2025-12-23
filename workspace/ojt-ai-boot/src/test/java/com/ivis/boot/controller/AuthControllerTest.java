package com.ivis.boot.controller;

import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.boot.service.AuthService;
import com.ivis.component.auth.JwtUtil;
import com.ivis.component.web.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AuthController 単体テスト
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AuthController authController;

    @Test
    @DisplayName("正しい認証情報でログインが成功する")
    void login_WithValidCredentials_ShouldReturnSuccess() {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password123");

        LoginResponse response = new LoginResponse("test-token", "admin");
        when(authService.login(any(LoginRequest.class))).thenReturn(Mono.just(response));

        // When
        Mono<ApiResponse<LoginResponse>> result = authController.login(request);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(apiResponse -> 
                    apiResponse.getCode() == 200 &&
                    apiResponse.getData().getToken().equals("test-token") &&
                    apiResponse.getData().getUsername().equals("admin")
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("無効な認証情報でログインが失敗する")
    void login_WithInvalidCredentials_ShouldReturnError() {
        // Given
        LoginRequest request = new LoginRequest();
        request.setUsername("invalid");
        request.setPassword("wrong");

        when(authService.login(any(LoginRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("Invalid username or password")));

        // When
        Mono<ApiResponse<LoginResponse>> result = authController.login(request);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(apiResponse -> 
                    apiResponse.getCode() == 401 &&
                    apiResponse.getMessage().contains("Invalid")
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("ログアウトが成功する")
    void logout_WithValidToken_ShouldReturnSuccess() {
        // Given
        String authHeader = "Bearer test-token";
        when(authService.logout("test-token")).thenReturn(Mono.empty());

        // When
        Mono<ApiResponse<Void>> result = authController.logout(authHeader);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(apiResponse -> apiResponse.getCode() == 200)
                .verifyComplete();
    }

    @Test
    @DisplayName("無効なヘッダーでログアウトしても成功する（静かに無視）")
    void logout_WithInvalidHeader_ShouldReturnSuccess() {
        // Given
        String authHeader = "InvalidHeader";

        // When
        Mono<ApiResponse<Void>> result = authController.logout(authHeader);

        // Then
        StepVerifier.create(result)
                .expectNextMatches(apiResponse -> apiResponse.getCode() == 200)
                .verifyComplete();
    }
}
