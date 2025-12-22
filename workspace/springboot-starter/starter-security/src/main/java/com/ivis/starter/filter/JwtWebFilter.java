package com.ivis.starter.filter;

import com.ivis.component.auth.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Slf4j
@Component
public class JwtWebFilter implements WebFilter {

    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redisTemplate;

    public JwtWebFilter(JwtUtil jwtUtil, ReactiveStringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String username = jwtUtil.extractUsername(token);
                if (username != null && jwtUtil.validateToken(token, username)) {
                    // Redisでトークンの有効性を確認
                    String redisKey = "auth:token:" + username;
                    return redisTemplate.opsForValue().get(redisKey)
                            .filter(storedToken -> storedToken.equals(token))
                            .flatMap(storedToken -> {
                                UsernamePasswordAuthenticationToken auth = 
                                    new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());
                                return chain.filter(exchange)
                                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                            })
                            .switchIfEmpty(chain.filter(exchange)); // トークンがRedisに存在しないか、不一致
                }
            } catch (Exception e) {
                // トークンが無効な場合、警告ログを記録してSecurityに処理させる（401）
                log.warn("JWT validation failed: {}", e.getMessage());
            }
        }
        return chain.filter(exchange);
    }
}
