package com.ivis.boot.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Redisにキャッシュするユーザー情報
 * JWT認証後、ユーザーの詳細情報をキャッシュして、DBクエリを削減する
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCacheInfo implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * ユーザー名
     */
    private String username;
    
    /**
     * ユーザーID（実際のDB連携時に使用）
     */
    private Long userId;
    
    /**
     * ロール（例：ADMIN, USER）
     */
    private List<String> roles;
    
    /**
     * 権限リスト（例：READ_USER, WRITE_USER）
     */
    private List<String> permissions;
    
    /**
     * ログイン時刻
     */
    private LocalDateTime loginTime;
    
    /**
     * 最終アクセス時刻
     */
    private LocalDateTime lastAccessTime;
}
