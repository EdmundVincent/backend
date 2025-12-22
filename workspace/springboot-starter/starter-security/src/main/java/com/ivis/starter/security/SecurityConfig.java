package com.ivis.starter.security;

import com.ivis.starter.filter.JwtWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JwtWebFilter jwtWebFilter) {
        return http
            // 1. CSRFを無効化（セッションCookieではなくJWTを使用するため）
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            
            // 2. CORS設定を有効化
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // 3. フォームログイン（デフォルトのページ）とHTTP Basic認証を無効化
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

            // 4. ステートレスセッション（HttpSessionなし）
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

            // 5. 認可ルール
            .authorizeExchange(exchanges -> exchanges
                // 公開エンドポイント（ログイン、Swagger、設定など）
                .pathMatchers("/api/auth/**", "/api/public/**", "/login").permitAll()
                // その他のすべてのエンドポイントは認証が必要
                .anyExchange().authenticated()
            )

            // 6. 認証の前にJWTフィルターを追加
            .addFilterAt(jwtWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            
            .build();
    }

    /**
     * CORS設定
     * フロントエンドからのクロスオリジンリクエストを許可
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 許可するオリジン（開発環境：Vite, React, Angularなど）
        configuration.setAllowedOriginPatterns(Arrays.asList(
            "http://localhost:*",      // すべてのlocalhostポート
            "http://127.0.0.1:*",       
            "https://localhost:*"
        ));
        
        // 許可するHTTPメソッド
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"
        ));
        
        // 許可するヘッダー
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"
        ));
        
        // 認証情報（Cookieなど）を許可
        configuration.setAllowCredentials(true);
        
        // プリフライトリクエストのキャッシュ時間（秒）
        configuration.setMaxAge(3600L);
        
        // レスポンスで公開するヘッダー
        configuration.setExposedHeaders(Arrays.asList(
            "Authorization",
            "Content-Disposition"
        ));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
