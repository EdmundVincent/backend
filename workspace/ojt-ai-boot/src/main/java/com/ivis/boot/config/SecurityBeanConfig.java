package com.ivis.boot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;

/**
 * R2DBC 監査設定
 * PasswordEncoder は component モジュールの SecurityComponentConfig で定義
 */
@Configuration
@EnableR2dbcAuditing
public class SecurityBeanConfig {
    // PasswordEncoder bean is provided by SecurityComponentConfig in component module
}
