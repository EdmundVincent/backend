package com.ivis.boot.repository;

import com.ivis.boot.entity.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * ユーザーリポジトリ
 * R2DBC によるリアクティブデータアクセス
 */
@Repository
public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    /**
     * ユーザー名でユーザーを検索
     */
    Mono<User> findByUsername(String username);

    /**
     * メールアドレスでユーザーを検索
     */
    Mono<User> findByEmail(String email);

    /**
     * ユーザー名の存在チェック
     */
    Mono<Boolean> existsByUsername(String username);

    /**
     * メールアドレスの存在チェック
     */
    Mono<Boolean> existsByEmail(String email);

    /**
     * 有効なユーザーを取得
     */
    @Query("SELECT * FROM users WHERE username = :username AND enabled = true")
    Mono<User> findByUsernameAndEnabled(String username);
}
