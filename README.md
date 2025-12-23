# OJT AI Feature Backend

## 📋 プロジェクト概要

本プロジェクトは、Spring Boot 3.4.1 + WebFlux をベースに構築されたリアクティブな RAG (Retrieval-Augmented Generation) バックエンドサービスです。

### 主な機能
- **JWT認証**: セキュアなトークンベース認証
- **RAGパイプライン**: ドキュメント取り込み→チャンク→ベクトル検索→回答生成
- **リアクティブアーキテクチャ**: Spring WebFlux + R2DBC による非同期処理
- **マイクロサービス対応**: Kafka(Redpanda) によるイベント駆動

## 🏗️ プロジェクト構造

```
ojt-ai-feature-backend/
├── docker-compose.yml           # 統合Docker Compose設定
├── .env.example                 # 環境変数テンプレート
├── dockerfile-springboot/       # Spring Boot用Dockerfile
│   ├── Dockerfile
│   └── docker-compose.yml       # 開発用（参考）
├── ojt-ai-feature-ragworker/    # C++ RAG Workerモジュール
│   └── dockerfiles/rag-worker/
│       ├── compose/             # RAG Worker用Docker Compose
│       └── scripts/             # 初期化スクリプト
│           └── init_db.sql      # DB初期化（users + kb_document + kb_chunk）
└── workspace/                   # Spring Bootワークスペース
    ├── pom.xml                  # 親POM
    ├── component/               # 共通コンポーネント
    │   └── src/main/java/com/ivis/component/
    │       ├── auth/            # JWT認証（JwtUtil, JwtFilter）
    │       ├── exception/       # 例外処理（BusinessException, ResourceNotFoundException）
    │       ├── minio/           # MinIO統合
    │       └── web/             # Web共通（ApiResponse, CorsConfig）
    ├── dependence/              # 依存関係管理
    ├── springboot-starter/      # カスタムStarter
    │   ├── starter-core/        # コアStarter
    │   ├── starter-flux/        # WebFlux Starter
    │   └── starter-security/    # Security Starter
    └── ojt-ai-boot/             # メインアプリケーション
        └── src/main/java/com/ivis/boot/
            ├── OjtAiBootApplication.java
            ├── config/          # 設定クラス
            ├── controller/      # REST API（AuthController, HelloController）
            ├── dto/             # DTOクラス
            ├── entity/          # R2DBCエンティティ（User）
            ├── repository/      # R2DBCリポジトリ（UserRepository）
            └── service/         # ビジネスロジック
                ├── AuthService.java     # 認証サービス
                └── llm/                 # LLMサービス
                    ├── LlmService.java             # インターフェース
                    └── AzureOpenAiLlmService.java  # Azure実装
```

## 🛠️ 技術スタック

| コンポーネント | 技術 | 用途 |
|------|------|------|
| **フレームワーク** | Spring Boot 3.4.1 + WebFlux | リアクティブWeb |
| **データベース** | PostgreSQL 16 + R2DBC | リレーショナルデータ |
| **認証** | JWT + BCrypt | トークン認証 + パスワード暗号化 |
| **キャッシュ** | Redis | セッション + トークン管理 |
| **メッセージキュー** | Redpanda (Kafka互換) | 非同期処理 |
| **ベクトルDB** | Qdrant | RAG検索 |
| **オブジェクトストレージ** | MinIO | ファイルアップロード |
| **グラフDB** | Neo4j 5.15 | ナレッジグラフ（オプション） |
| **JDK** | OpenJDK 21 | 実行環境 |

## 🚀 クイックスタート

### 前提条件
- Docker & Docker Compose
- JDK 21+
- Maven 3.8+

### 1. 環境変数の設定

```bash
cp .env.example .env
# .envファイルを編集して、必要な値を設定
```

### 2. Dockerサービスの起動

```bash
docker-compose up -d
```

サービス確認：
```bash
docker-compose ps
```

**サービスアクセス：**
| サービス | URL/Port | 備考 |
|---------|----------|------|
| Spring Boot API | http://localhost:8080 | メインAPI |
| PostgreSQL | localhost:5432 | DB: rag_db |
| Redis | localhost:6379 | キャッシュ |
| Redpanda | localhost:9092 | Kafka API |
| MinIO Console | http://localhost:9001 | オブジェクトストレージ |
| Qdrant | http://localhost:6333 | ベクトルDB |
| Neo4j Browser | http://localhost:7474 | グラフDB |

### 3. ローカル開発

```bash
cd workspace
mvn clean install -DskipTests
mvn spring-boot:run -pl ojt-ai-boot
```

## 📖 API エンドポイント

### 認証 API

**ログイン：**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "123456"}'
```

**レスポンス：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "username": "admin"
  }
}
```

**ユーザー登録：**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "newuser",
    "password": "password123",
    "email": "newuser@example.com"
  }'
```

**Hello エンドポイント（認証必須）：**
```bash
curl http://localhost:8080/api/hello \
  -H "Authorization: Bearer <your-token>"
```

### RAG API（開発中）

- `POST /api/rag/ingest` - ドキュメント取り込み
- `POST /api/rag/search` - ベクトル検索
- `POST /api/rag/answer` - 回答生成

## 🧪 テスト

```bash
cd workspace
mvn test
```

テストカバレッジ：
```bash
mvn test jacoco:report
```

## 🔒 セキュリティ

### デフォルト認証情報（開発用）
- **ユーザー**: admin
- **パスワード**: 123456

⚠️ **本番環境では必ず変更してください！**

### 環境変数

| 変数 | 説明 | デフォルト |
|------|------|---------|
| `JWT_SECRET` | JWTトークン署名キー | (必須) |
| `POSTGRES_PASSWORD` | PostgreSQLパスワード | rag_password |
| `MINIO_ROOT_PASSWORD` | MinIOパスワード | minio123 |
| `NEO4J_PASSWORD` | Neo4jパスワード | neo4j_password |
| `AZURE_OPENAI_API_KEY` | Azure OpenAI APIキー | (必須) |

## 📁 データベーススキーマ

### PostgreSQL

```sql
-- ユーザーテーブル
CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,  -- BCrypt暗号化
  email VARCHAR(100) UNIQUE,
  enabled BOOLEAN DEFAULT TRUE,
  roles VARCHAR(255) DEFAULT 'ROLE_USER',
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ドキュメントメタデータ
CREATE TABLE kb_document (...);

-- ドキュメントチャンク
CREATE TABLE kb_chunk (...);
```

## 🐛 トラブルシューティング

### コンテナログ確認
```bash
docker-compose logs -f rag-api
docker-compose logs -f postgres
```

### データベース接続テスト
```bash
docker exec -it ivis-postgres psql -U rag_user -d rag_db
```

### Redisキャッシュ確認
```bash
docker exec -it ivis-redis redis-cli
> KEYS *
```

### Kafkaトピック確認
```bash
docker exec -it ivis-kafka-tools kcat -b redpanda:9092 -L
```

## 📝 開発ガイドライン

### コード規約
- Reactive Streams (Project Reactor) を使用
- `Mono<T>` / `Flux<T>` を返却型として使用
- ブロッキング操作は避ける

### 例外処理
- `BusinessException` - ビジネスロジックエラー
- `ResourceNotFoundException` - リソース未発見

### APIレスポンス
```java
ApiResponse.success(data);    // 成功
ApiResponse.error(code, msg); // エラー
```

## 👥 開発チーム

本プロジェクトは3名のバックエンドチームによる共同開発です。

## 📄 ライセンス

Internal Use Only - 社内利用限定

---

最終更新日：2025年12月
