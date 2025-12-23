# Spring Boot 与 C++ RAG Worker 集成指南

## 架构概览

```
前端 → Spring Boot API → Kafka → C++ RAG Worker → Qdrant/PostgreSQL
         ↓                          ↓
       MinIO                    Azure OpenAI
         ↓                          ↓
       Redis (结果缓存)        生成 Embeddings/答案
```

## 已实现的功能

### ✅ 1. 文件上传（API1）
**接口**: `POST /api/file/upload`

**功能流程**:
1. 接收文件上传（支持 PDF/DOC/TXT/图片）
2. 存储到 MinIO 对象存储
3. 发送文档摄取事件到 Kafka `doc_ingest` topic
4. C++ RAG Worker 消费消息，进行分块、向量化、存入 Qdrant

**请求示例**:
```bash
curl -X POST http://localhost:8080/api/file/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@document.pdf" \
  -F "tenantId=tenant-001" \
  -F "kbId=kb-001"
```

**响应**:
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "docId": "uuid",
    "status": "processing",
    "message": "File uploaded and queued for processing"
  }
}
```

---

### ✅ 2. RAG 搜索（API2）
**接口**: `POST /api/rag/search`

**功能流程**:
1. 接收搜索查询
2. 发送到 Kafka `rag_search_request` topic
3. C++ Worker 生成查询向量并从 Qdrant 检索相关文档
4. 结果写入 `rag_search_result` topic
5. Spring Boot 消费结果并存入 Redis

**请求示例**:
```bash
curl -X POST http://localhost:8080/api/rag/search \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "什么是向量数据库？",
    "topK": 5,
    "tenantId": "tenant-001",
    "kbId": "kb-001"
  }'
```

**响应**:
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "requestId": "search-uuid",
    "status": "processing"
  }
}
```

---

### ✅ 3. RAG 答案生成（API3）
**接口**: `POST /api/rag/answer`

**功能流程**:
1. 接收问题和上下文
2. 发送到 Kafka `rag_answer_request` topic
3. C++ Worker 调用 Azure OpenAI 生成答案
4. 结果写入 `rag_answer_result` topic
5. Spring Boot 消费结果并存入 Redis

**请求示例**:
```bash
curl -X POST http://localhost:8080/api/rag/answer \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "如何使用向量数据库？",
    "context": [
      {
        "doc_id": "doc-123",
        "snippet": "向量数据库用于存储和检索..."
      }
    ],
    "tenantId": "tenant-001"
  }'
```

**响应**:
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "requestId": "answer-uuid",
    "status": "processing"
  }
}
```

---

### ✅ 4. 结果轮询（API0）
**接口**: `GET /api/rag/result/{requestId}`

**功能**: 从 Redis 获取 RAG Worker 处理完成的结果

**请求示例**:
```bash
curl http://localhost:8080/api/rag/result/search-uuid \
  -H "Authorization: Bearer <token>"
```

**成功响应**:
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "request_id": "search-uuid",
    "hits": [
      {
        "doc_id": "doc-10",
        "score": 0.92,
        "title": "向量数据库指南",
        "snippet": "向量数据库用于...",
        "url": "https://example.com/doc"
      }
    ],
    "latency_ms": 120
  }
}
```

**处理中响应**:
```json
{
  "code": 404,
  "message": "Result not ready yet, please try again later"
}
```

---

## Kafka Topics（与 C++ Worker 对接）

| Topic | 方向 | 用途 | 消息格式 |
|-------|------|------|---------|
| `doc_ingest` | Spring Boot → Worker | 文档摄取 | `{doc_id, tenant_id, kb_id, s3_path, filename}` |
| `rag_search_request` | Spring Boot → Worker | 搜索请求 | `{request_id, query, top_k, metadata}` |
| `rag_search_result` | Worker → Spring Boot | 搜索结果 | `{request_id, hits, latency_ms}` |
| `rag_answer_request` | Spring Boot → Worker | 答案请求 | `{request_id, question, context, metadata}` |
| `rag_answer_result` | Worker → Spring Boot | 答案结果 | `{request_id, answer, citations}` |
| `rag_failed` | Worker → Spring Boot | 失败消息 | `{request_id, error_code, error_message}` |

---

## 环境配置

### 必需的服务（Docker Compose）

**Spring Boot 需要**:
- Redis（缓存结果）
- Redpanda/Kafka（消息队列）
- MinIO（对象存储）

**C++ RAG Worker 需要**:
- PostgreSQL（元数据存储）
- Qdrant（向量数据库）
- MinIO（读取文档）
- Redpanda/Kafka（消息队列）
- Azure OpenAI（Embedding + Chat）

### application.yml 配置

```yaml
spring:
  kafka:
    bootstrap-servers: redpanda:9092
  data:
    redis:
      host: ivis-redis
      port: 6379

minio:
  endpoint: http://minio:9000
  access-key: minio
  secret-key: minio123
  bucket: rag-docs
```

---

## 启动步骤

### 1. 启动基础设施
```bash
# 启动 RAG Worker 的 Docker Compose（包含 Redpanda, Qdrant, PostgreSQL）
cd ojt-ai-feature-ragworker/dockerfiles/rag-worker/compose
docker-compose -f docker-compose.dev.yml up -d

# 或合并到主 docker-compose.yml
```

### 2. 启动 Spring Boot
```bash
cd workspace
mvn clean install -DskipTests
mvn spring-boot:run -pl ojt-ai-boot
```

### 3. 启动 C++ RAG Worker
```bash
cd ojt-ai-feature-ragworker/workspace/rag-worker
# 按照 README.md 编译并运行
./rag-worker-dev --consume-once
```

---

## 测试流程

### 完整 RAG 流程测试

```bash
# 1. 登录获取 Token
TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | jq -r '.data.token')

# 2. 上传文档
DOC_ID=$(curl -X POST http://localhost:8080/api/file/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test.pdf" \
  -F "tenantId=tenant-001" \
  -F "kbId=kb-001" | jq -r '.data.docId')

echo "Document uploaded: $DOC_ID"

# 3. 等待文档处理完成（查看 C++ Worker 日志）
sleep 30

# 4. RAG 搜索
SEARCH_REQ_ID=$(curl -X POST http://localhost:8080/api/rag/search \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "文档的主要内容是什么？",
    "topK": 3,
    "tenantId": "tenant-001",
    "kbId": "kb-001"
  }' | jq -r '.data.requestId')

echo "Search request ID: $SEARCH_REQ_ID"

# 5. 轮询搜索结果（等待 C++ Worker 处理）
sleep 5
SEARCH_RESULT=$(curl http://localhost:8080/api/rag/result/$SEARCH_REQ_ID \
  -H "Authorization: Bearer $TOKEN")

echo "Search result: $SEARCH_RESULT"

# 6. 生成答案（使用搜索结果作为上下文）
ANSWER_REQ_ID=$(curl -X POST http://localhost:8080/api/rag/answer \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"question\": \"文档的主要内容是什么？\",
    \"context\": $(echo $SEARCH_RESULT | jq '.data.hits'),
    \"tenantId\": \"tenant-001\"
  }" | jq -r '.data.requestId')

echo "Answer request ID: $ANSWER_REQ_ID"

# 7. 轮询答案结果
sleep 10
curl http://localhost:8080/api/rag/result/$ANSWER_REQ_ID \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## 故障排查

### Kafka 连接问题
```bash
# 检查 Redpanda 是否运行
docker ps | grep redpanda

# 测试 Kafka 连接
docker exec kafka-tools kcat -b redpanda:9092 -L
```

### MinIO 连接问题
```bash
# 检查 MinIO 是否运行
curl http://localhost:9000/minio/health/live

# 检查 bucket 是否存在
docker exec mc mc ls minio-local/rag-docs
```

### Redis 结果查询
```bash
# 查看 Redis 中的结果
docker exec ivis-redis redis-cli KEYS "rag:result:*"
docker exec ivis-redis redis-cli GET "rag:result:search-uuid"
```

### C++ Worker 日志
```bash
# 查看 Worker 处理日志
docker logs rag-worker-dev
```

---

## 下一步开发建议

1. **WebSocket 实时推送**: 替代轮询机制，实时推送结果给前端
2. **批量文档上传**: 支持一次上传多个文件
3. **文档状态查询**: 查询文档处理进度（chunking/embedding/ready）
4. **知识库管理**: CRUD 知识库和租户配置
5. **搜索历史记录**: 保存用户的搜索和问答历史
6. **流式答案返回**: 使用 SSE 或 WebSocket 流式返回 AI 生成的答案

---

## 项目文件位置

```
d:/backend/ojt-ai-feature-backend/
├── workspace/                          # Spring Boot 项目
│   ├── ojt-ai-boot/
│   │   ├── src/main/java/com/ivis/boot/
│   │   │   ├── config/
│   │   │   │   ├── KafkaProducerConfig.java
│   │   │   │   ├── KafkaConsumerConfig.java
│   │   │   │   └── MinIOConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── FileController.java       # 文件上传
│   │   │   │   └── RagController.java        # RAG 搜索/答案
│   │   │   ├── service/
│   │   │   │   ├── MinIOService.java
│   │   │   │   ├── RagKafkaProducerService.java
│   │   │   │   └── RagKafkaConsumerService.java
│   │   │   └── dto/
│   │   │       ├── file/FileUploadResponse.java
│   │   │       └── rag/*.java
│   │   └── src/main/resources/
│   │       └── application.yml               # 已更新配置
│   └── pom.xml                                # 已添加 MinIO 依赖
│
└── ojt-ai-feature-ragworker/                 # C++ RAG Worker（领导完成）
    └── workspace/rag-worker/
        ├── CONTRACT.md                        # Kafka 接口规范
        └── README.md                          # C++ Worker 使用说明
```
