package com.ivis.boot.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * ユーザーエンティティ
 * PostgreSQL の users テーブルにマッピング
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
public class User {

    @Id
    private Long id;

    @Column("username")
    private String username;

    @Column("password")
    private String password;

    @Column("email")
    private String email;

    @Column("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column("roles")
    private String roles; // カンマ区切り（例: "ADMIN,USER"）

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * ロールを配列として取得
     */
    public String[] getRolesArray() {
        if (roles == null || roles.isEmpty()) {
            return new String[]{"USER"};
        }
        return roles.split(",");
    }
}
