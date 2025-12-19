package com.ivis.boot.service;

import com.ivis.boot.dto.auth.LoginRequest;
import com.ivis.boot.dto.auth.LoginResponse;
import com.ivis.component.auth.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public Mono<LoginResponse> login(LoginRequest request) {
        // Mock Logic: Check if username is "admin" and password is "123456"
        // In real world, you should fetch user from Database
        return Mono.just(request)
            .filter(req -> "admin".equals(req.getUsername()))
            .filter(req -> "123456".equals(req.getPassword())) // Plain text check for mock
            .map(req -> {
                String token = jwtUtil.generateToken(req.getUsername());
                return new LoginResponse(token, req.getUsername());
            })
            .switchIfEmpty(Mono.error(new RuntimeException("Invalid username or password")));
    }
}
