package com.ivis.boot.service;

import com.ivis.component.auth.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 単体テスト
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;
    private static final String SECRET = "test-secret-key-for-unit-testing-at-least-32-characters-long";
    private static final long EXPIRATION = 3600000L; // 1 hour

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, EXPIRATION);
    }

    @Test
    @DisplayName("トークン生成が正常に動作する")
    void generateToken_ShouldReturnValidToken() {
        // Given
        String username = "testuser";

        // When
        String token = jwtUtil.generateToken(username);

        // Then
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.split("\\.").length == 3); // JWT format: header.payload.signature
    }

    @Test
    @DisplayName("トークンからユーザー名を正しく抽出できる")
    void extractUsername_ShouldReturnCorrectUsername() {
        // Given
        String username = "testuser";
        String token = jwtUtil.generateToken(username);

        // When
        String extractedUsername = jwtUtil.extractUsername(token);

        // Then
        assertEquals(username, extractedUsername);
    }

    @Test
    @DisplayName("有効なトークンの検証が成功する")
    void validateToken_WithValidToken_ShouldReturnTrue() {
        // Given
        String username = "testuser";
        String token = jwtUtil.generateToken(username);

        // When
        boolean isValid = jwtUtil.validateToken(token, username);

        // Then
        assertTrue(isValid);
    }

    @Test
    @DisplayName("異なるユーザー名でトークン検証が失敗する")
    void validateToken_WithDifferentUsername_ShouldReturnFalse() {
        // Given
        String username = "testuser";
        String token = jwtUtil.generateToken(username);

        // When
        boolean isValid = jwtUtil.validateToken(token, "differentuser");

        // Then
        assertFalse(isValid);
    }

    @Test
    @DisplayName("期限切れトークンの検証が失敗する")
    void validateToken_WithExpiredToken_ShouldThrowException() {
        // Given: 非常に短い有効期限で JwtUtil を作成
        JwtUtil shortLivedJwtUtil = new JwtUtil(SECRET, 1L); // 1ms
        String token = shortLivedJwtUtil.generateToken("testuser");

        // Wait for token to expire
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // When & Then
        assertThrows(Exception.class, () -> {
            shortLivedJwtUtil.validateToken(token, "testuser");
        });
    }

    @Test
    @DisplayName("トークンの有効期限を正しく抽出できる")
    void extractExpiration_ShouldReturnFutureDate() {
        // Given
        String token = jwtUtil.generateToken("testuser");

        // When
        var expiration = jwtUtil.extractExpiration(token);

        // Then
        assertNotNull(expiration);
        assertTrue(expiration.getTime() > System.currentTimeMillis());
    }
}
