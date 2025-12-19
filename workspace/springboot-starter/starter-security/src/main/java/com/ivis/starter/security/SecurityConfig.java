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
            // 1. Disable CSRF (because we use JWT, not Session Cookies)
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            
            // 2. Disable Form Login (the ugly page) & HTTP Basic
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

            // 3. Stateless Session (No HttpSession)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

            // 4. Authorize Rules
            .authorizeExchange(exchanges -> exchanges
                // Public endpoints (Login, Swagger, Config, etc.)
                .pathMatchers("/api/hello", "/api/auth/**", "/api/public/**", "/login").permitAll()
                // All other endpoints need authentication
                .anyExchange().authenticated()
            )

            // 5. Add JWT Filter before Authentication
            .addFilterAt(jwtWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            
            .build();
    }
}
