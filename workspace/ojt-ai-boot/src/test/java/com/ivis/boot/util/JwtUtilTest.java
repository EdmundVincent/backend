package com.ivis.boot.util;

import com.ivis.component.auth.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 単体テスト
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;
    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm";
    private static final long EXPIRATION_MS = 3600000L; // 1 hour

    @BeforeEach
    void setUp() {
        // JwtUtil requires (String secret, long expirationTime) in constructor
        jwtUtil = new JwtUtil(SECRET, EXPIRATION_MS);
    }

    @Test
    @DisplayName("トークン生成が成功する")
    void generateToken_ShouldReturnValidToken() {
        String username = "testuser";
        String token = jwtUtil.generateToken(username);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.split("\\.").length == 3); // JWT format: header.payload.signature
    }

    @Test
    @DisplayName("トークンからユーザー名を抽出できる")
    void extractUsername_ShouldReturnCorrectUsername() {
        String username = "testuser";
        String token = jwtUtil.generateToken(username);

        String extractedUsername = jwtUtil.extractUsername(token);

        assertEquals(username, extractedUsername);
    }

    @Test
    @DisplayName("有効なトークンが検証に合格する")
    void validateToken_WithValidToken_ShouldReturnTrue() {
        String username = "testuser";
        String token = jwtUtil.generateToken(username);

        boolean isValid = jwtUtil.validateToken(token, username);

        assertTrue(isValid);
    }

    @Test
    @DisplayName("無効なトークンが検証に失敗する")
    void validateToken_WithInvalidToken_ShouldReturnFalse() {
        String invalidToken = "invalid.token.here";

        assertThrows(Exception.class, () -> jwtUtil.extractUsername(invalidToken));
    }

    @Test
    @DisplayName("異なるユーザー名でトークン検証が失敗する")
    void validateToken_WithWrongUsername_ShouldReturnFalse() {
        String token = jwtUtil.generateToken("user1");

        boolean isValid = jwtUtil.validateToken(token, "user2");

        assertFalse(isValid);
    }

    @Test
    @DisplayName("トークンから有効期限を抽出できる")
    void extractExpiration_ShouldReturnFutureDate() {
        String token = jwtUtil.generateToken("testuser");

        Date expiration = jwtUtil.extractExpiration(token);

        assertNotNull(expiration);
        assertTrue(expiration.after(new Date()));
    }
}
