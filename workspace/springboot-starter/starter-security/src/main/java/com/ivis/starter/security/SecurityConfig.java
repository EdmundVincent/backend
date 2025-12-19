package com.ivis.starter.security;

import com.ivis.starter.filter.JwtWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JwtWebFilter jwtWebFilter) {
        return http
            // 1. CSRFを無効化（セッションCookieではなくJWTを使用するため）
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            
            // 2. フォームログイン（デフォルトのページ）とHTTP Basic認証を無効化
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

            // 3. ステートレスセッション（HttpSessionなし）
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

            // 4. 認可ルール
            .authorizeExchange(exchanges -> exchanges
                // 公開エンドポイント（ログイン、Swagger、設定など）
                .pathMatchers("/api/auth/**", "/api/public/**", "/login").permitAll()
                // その他のすべてのエンドポイントは認証が必要
                .anyExchange().authenticated()
            )

            // 5. 認証の前にJWTフィルターを追加
            .addFilterAt(jwtWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            
            .build();
    }
}
