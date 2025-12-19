# IVIS AI Translator - インテリジェント・マルチモーダル翻訳チャットボット

## 📋 プロジェクト概要

IVIS AI Translatorは、Spring Boot 3.4.1をベースに構築されたインテリジェント翻訳サービスであり、以下の機能をサポートしています：
- **マルチモーダル入力**：テキストおよび画像の翻訳
- **自動言語検出**：ソース言語の自動識別
- **RAG拡張**：ベクトルデータベースに基づく知識検索
- **非同期処理**：メッセージキュー駆動の高効率アーキテクチャ
- **ナレッジグラフ**：Neo4jによるセマンティック強化

## 🏗️ プロジェクトアーキテクチャ

### マルチモジュール構成
```
ojt-ai/
├── pom.xml                  # ルートPOM（バージョン管理）
├── common-bom/              # BOMモジュール（依存関係管理）
├── common-dependencies/     # 共通依存関係定義
├── common-starter/          # カスタムStarter（自動設定）
├── translator-api/          # コア翻訳サービスモジュール
└── dockerfile-springboot/   # Dockerコンテナ化設定
    ├── docker-compose.yml   # コンテナオーケストレーション
    ├── Dockerfile           # マルチステージビルド
    └── .devcontainer/       # VS Codeコンテナ開発設定
```

### 技術スタック

| コンポーネント | 技術 | 用途 |
|------|------|------|
| **フレームワーク** | Spring Boot 3.4.1 + Spring Cloud | Webフレームワーク |
| **データベース** | PostgreSQL 16 + pgvector | リレーショナルデータ + ベクトルストア |
| **キャッシュ** | Redis 7.2 | キャッシュ & セッション管理 |
| **メッセージキュー** | Kafka + Zookeeper | 非同期処理 & 分離 |
| **グラフデータベース** | Neo4j 5.15 | ナレッジグラフ & エンティティ関係 |
| **オブジェクトストレージ** | MinIO | 画像ストレージ |
| **言語検出** | langdetect | 自動言語識別 |
| **JDK** | OpenJDK 17 | 実行環境 |

## 🚀 クイックスタート

### 前提条件
- Docker & Docker Compose
- JDK 17+
- Maven 3.8+
- VS Code (任意、Remote Containersを使用する場合)

### 1. コンテナ環境の起動

```bash
cd ojt-ai/dockerfile-springboot
docker-compose up -d
```

全サービスの稼働状態を確認：
```bash
docker-compose ps
```

**サービスアクセスアドレス：**
- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`
- Neo4j: `localhost:7474` (ユーザー: neo4j, パスワード: ivis_neo4j_password)
- MinIO: `localhost:9001` (ユーザー: minioadmin, パスワード: minioadmin)

### 2. プロジェクトのビルド

```bash
cd ojt-ai
mvn clean install
```

### 3. アプリケーションの実行

```bash
cd translator-api
mvn spring-boot:run
```

起動後にアクセス: `http://localhost:8080`

## 📖 API ドキュメント

### 翻訳リクエストの送信

**テキスト翻訳：**
```bash
curl -X POST http://localhost:8080/api/v1/translations/submit \
  -H "Content-Type: application/json" \
  -d '{
    "requestType": "TEXT",
    "sourceText": "Hello, world!",
    "sourceLanguage": "auto",
    "targetLanguage": "ja"
  }'
```

**画像翻訳：**
```bash
curl -X POST http://localhost:8080/api/v1/translations/submit \
  -H "Content-Type: application/json" \
  -d '{
    "requestType": "IMAGE",
    "imageUrl": "https://example.com/image.jpg",
    "targetLanguage": "ja"
  }'
```

### 翻訳結果の取得

```bash
curl http://localhost:8080/api/v1/translations/{requestId}
```

## 📊 データベース設計

### PostgreSQL 主要テーブル

| テーブル名 | 用途 |
|------|------|
| `users` | ユーザー情報 |
| `translation_requests` | 翻訳リクエスト履歴 |
| `knowledge_vectors` | RAGナレッジベース（pgvector） |

### Neo4j ノードタイプ

- `User`: ユーザーノード
- `Language`: 言語ノード
- `Term`: 用語ノード
- `Document`: ドキュメントノード

## 🔧 開発設定

### VS Code Remote Containersの使用

1. "Dev Containers" 拡張機能をインストール
2. `F1` キーを押し、"Dev Containers: Reopen in Container" を入力
3. システムが自動的に開発環境を設定し、docker-composeを起動します

### モジュール依存関係

```
translator-api
    └── common-starter
        ├── common-dependencies
        │   └── common-bom
        └── spring-boot-starters
```

## 🛠️ ビルドとデプロイ

### Mavenによるパッケージング

```bash
mvn clean package -DskipTests
```

### Dockerイメージのビルド

```bash
cd dockerfile-springboot
docker build -t ivis-translator:latest .
```

### Docker Composeによるデプロイ

```bash
docker-compose -f docker-compose.yml up -d
```

## 📝 設定ファイル説明

### application.yml

- **データベース設定**: PostgreSQL接続プール、JPAダイアレクト
- **Redis設定**: 接続タイムアウト、プールサイズ
- **Kafka設定**: プロデューサー・コンシューマー設定
- **Actuator監視**: ヘルスチェック、メトリクス収集

## 🔐 セキュリティ推奨事項

- 全てのデフォルトパスワード（Redis, Kafka, Neo4j, MinIO）を変更する
- 本番環境ではSSL/TLSを有効にする
- 認証・認可メカニズムを実装する
- 定期的にデータベースをバックアップする

## 📈 パフォーマンス最適化

- Redisによるホットデータのキャッシュ
- Kafkaによる翻訳タスクの非同期処理
- ベクトルインデックスによる類似検索の高速化
- 接続プールの最適化

## 🐛 トラブルシューティング

### コンテナ起動失敗
```bash
docker-compose logs -f [service-name]
```

### データベース接続失敗
```bash
docker exec ivis-postgres psql -U ivis_user -d ivis_translator
```

## 📚 参考リソース

- [Spring Boot 3.4.1 ドキュメント](https://spring.io/projects/spring-boot)
- [PostgreSQL pgvector拡張](https://github.com/pgvector/pgvector)
- [Neo4jドライバー](https://github.com/neo4j/neo4j-java-driver)
- [Kafkaドキュメント](https://kafka.apache.org/documentation/)

## 👥 チームメンバー

本プロジェクトは3名のバックエンドチームによる共同開発であり、社内のマイクロサービスアーキテクチャ標準に準拠しています。

## 📄 ライセンス

Internal Use Only - 社内利用限定

---

最終更新日：2025年12月15日
