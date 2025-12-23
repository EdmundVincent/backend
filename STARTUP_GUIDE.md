# 🚀 统一启动指南

## 📋 什么改变了？

### ✅ 已完成的整合

1. **删除旧的 Kafka** → 改用 Redpanda（更快更轻量）
2. **合并 docker-compose.yml** → 一个文件启动所有服务
3. **C++ RAG Worker** → 自动启动并消费 Kafka 消息
4. **统一网络** → 所有容器在同一个 `ivis-net` 网络

### 📦 现在的服务列表

| 服务 | 容器名 | 端口 | 用途 |
|------|--------|------|------|
| Redpanda | ivis-redpanda | 9092 | 消息队列（替代 Kafka） |
| Redis | ivis-redis | 6379 | 缓存（结果存储） |
| PostgreSQL | ivis-postgres | 5432 | 关系型数据库（文档元数据） |
| MinIO | ivis-minio | 9000, 9001 | 对象存储（文件上传） |
| Qdrant | ivis-qdrant | 6333 | 向量数据库（RAG 搜索） |
| Neo4j | ivis-neo4j | 7474, 7687 | 图数据库（可选） |
| C++ RAG Worker | ivis-rag-worker | - | 后台处理引擎 |
| Spring Boot API | ivis-rag-api | 8080 | REST API 服务 |

---

## 🔧 启动步骤

### 第 1 步：停止旧的容器

```powershell
# 停止并删除旧的 Kafka 容器
docker stop ivis-kafka ivis-zookeeper 2>$null
docker rm ivis-kafka ivis-zookeeper 2>$null

# 停止其他可能冲突的容器
docker stop ivis-rag-api ivis-postgres ivis-redis ivis-minio 2>$null
```

### 第 2 步：配置环境变量

编辑 `.env` 文件，**必须**配置 Azure OpenAI（RAG Worker 需要）：

```bash
# 打开 .env 文件
notepad d:\backend\ojt-ai-feature-backend\.env
```

**关键配置**（找你的领导要）：
```env
AZURE_OPENAI_ENDPOINT=https://your-endpoint.openai.azure.com
AZURE_OPENAI_API_KEY=your-api-key-here
AZURE_OPENAI_EMBEDDING_DEPLOYMENT=text-embedding-3-large
AZURE_OPENAI_CHAT_DEPLOYMENT=DeepSeek-V3.2
```

### 第 3 步：启动所有服务

```powershell
cd d:\backend\ojt-ai-feature-backend

# 启动所有服务（包括 C++ Worker）
docker-compose up -d

# 查看启动日志
docker-compose logs -f
```

**预计启动时间**：
- 基础服务：30 秒
- 初始化脚本：1 分钟
- C++ Worker：2-3 分钟（需要编译）
- Spring Boot：2-3 分钟（Maven 编译）

### 第 4 步：验证服务状态

```powershell
# 查看所有容器状态
docker-compose ps

# 应该看到所有服务都是 "Up" 或 "healthy"
```

**检查关键服务**：

```powershell
# 1. Redpanda（消息队列）
docker exec ivis-kafka-tools kcat -b redpanda:9092 -L

# 2. MinIO（对象存储）
curl http://localhost:9000/minio/health/live

# 3. Qdrant（向量数据库）
curl http://localhost:6333/collections

# 4. Spring Boot API
curl http://localhost:8080/api/hello

# 5. Redis（缓存）
docker exec ivis-redis redis-cli PING
```

---

## 🧪 完整测试流程

### 1. 登录获取 Token

```powershell
$loginResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" `
  -Method POST `
  -Body (@{ username = "admin"; password = "123456" } | ConvertTo-Json) `
  -ContentType "application/json"

$token = $loginResponse.data.token
Write-Host "Token: $token"
```

### 2. 上传文档

```powershell
# 创建测试文件
"这是一个测试文档。向量数据库用于存储和检索高维向量数据。" | Out-File -FilePath test.txt

# 上传文件
$form = @{
    file = Get-Item "test.txt"
    tenantId = "tenant-001"
    kbId = "kb-001"
}

$uploadResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/file/upload" `
  -Method POST `
  -Headers @{ Authorization = "Bearer $token" } `
  -Form $form

$docId = $uploadResponse.data.docId
Write-Host "Document uploaded: $docId"
```

### 3. 监控 C++ Worker 处理进度

```powershell
# 查看 Worker 日志
docker logs ivis-rag-worker --tail 50 -f

# 你应该看到类似的日志：
# [INFO] Consumed message from doc_ingest: doc_id=xxx
# [INFO] Chunking document...
# [INFO] Embedding chunks...
# [INFO] Upserting to Qdrant...
# [INFO] Document processed successfully
```

**等待 30-60 秒**让文档处理完成。

### 4. RAG 搜索

```powershell
$searchResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/rag/search" `
  -Method POST `
  -Headers @{ 
    Authorization = "Bearer $token"
    "Content-Type" = "application/json"
  } `
  -Body (@{
    query = "什么是向量数据库？"
    topK = 3
    tenantId = "tenant-001"
    kbId = "kb-001"
  } | ConvertTo-Json)

$searchRequestId = $searchResponse.data.requestId
Write-Host "Search request ID: $searchRequestId"
```

### 5. 轮询搜索结果

```powershell
# 等待 5 秒
Start-Sleep -Seconds 5

# 获取结果
$searchResult = Invoke-RestMethod -Uri "http://localhost:8080/api/rag/result/$searchRequestId" `
  -Headers @{ Authorization = "Bearer $token" }

$searchResult | ConvertTo-Json -Depth 10
```

### 6. 生成答案

```powershell
$answerResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/rag/answer" `
  -Method POST `
  -Headers @{ 
    Authorization = "Bearer $token"
    "Content-Type" = "application/json"
  } `
  -Body (@{
    question = "向量数据库有什么用途？"
    context = $searchResult.data.hits
    tenantId = "tenant-001"
  } | ConvertTo-Json)

$answerRequestId = $answerResponse.data.requestId
Write-Host "Answer request ID: $answerRequestId"
```

### 7. 轮询答案结果

```powershell
# 等待 10 秒（调用 AI 需要更长时间）
Start-Sleep -Seconds 10

# 获取答案
$answerResult = Invoke-RestMethod -Uri "http://localhost:8080/api/rag/result/$answerRequestId" `
  -Headers @{ Authorization = "Bearer $token" }

Write-Host "AI Answer:"
$answerResult.data.answer
```

---

## 🐛 故障排查

### 问题 1: 容器启动失败

```powershell
# 查看失败容器的日志
docker-compose logs <服务名>

# 常见原因：
# - 端口被占用 → 修改 docker-compose.yml 端口映射
# - 初始化脚本失败 → 检查 .env 配置
# - 依赖服务未就绪 → 等待 healthcheck 通过
```

### 问题 2: C++ Worker 编译失败

```powershell
# 进入容器手动编译
docker exec -it ivis-rag-worker bash
cd /app
mkdir -p build && cd build
cmake ..
make -j

# 如果缺少依赖，安装后重新编译
```

### 问题 3: Spring Boot 连接不上 Redpanda

```powershell
# 确认 Redpanda 正在运行
docker exec ivis-redpanda rpk cluster health

# 测试网络连通性
docker exec ivis-rag-api ping redpanda
```

### 问题 4: Azure OpenAI 调用失败

```powershell
# 检查 Worker 日志
docker logs ivis-rag-worker | Select-String "Azure"

# 确认 .env 配置正确
docker exec ivis-rag-worker env | Select-String "AZURE"

# 测试 API Key
curl -H "api-key: $AZURE_OPENAI_API_KEY" $AZURE_OPENAI_ENDPOINT
```

---

## 📊 监控命令

### 实时查看所有日志

```powershell
docker-compose logs -f
```

### 查看特定服务日志

```powershell
docker-compose logs -f rag-worker    # C++ Worker
docker-compose logs -f rag-api       # Spring Boot
docker-compose logs -f redpanda      # Kafka
```

### 检查 Kafka Topics

```powershell
docker exec ivis-redpanda rpk topic list
docker exec ivis-redpanda rpk topic consume doc_ingest --num 1
```

### 检查 Redis 缓存

```powershell
docker exec ivis-redis redis-cli KEYS "*"
docker exec ivis-redis redis-cli GET "rag:result:your-request-id"
```

### 检查 Qdrant 集合

```powershell
curl http://localhost:6333/collections
curl http://localhost:6333/collections/tenant-001__kb-001
```

---

## 🛑 停止服务

```powershell
# 停止所有服务
docker-compose down

# 停止并删除数据卷（⚠️ 会删除所有数据）
docker-compose down -v
```

---

## 📝 下一步开发建议

1. **前端集成**：使用 INTEGRATION_GUIDE.md 中的 API 文档
2. **WebSocket 推送**：替代轮询机制（提升用户体验）
3. **批量上传**：支持一次上传多个文件
4. **文档管理**：CRUD 知识库和文档状态查询
5. **监控面板**：Grafana + Prometheus 监控各服务状态

---

## 🆘 遇到问题？

1. 查看 `INTEGRATION_GUIDE.md` 的 API 文档
2. 查看 `ojt-ai-feature-ragworker/workspace/rag-worker/CONTRACT.md` 的 Kafka 接口规范
3. 查看 Docker 日志：`docker-compose logs -f`
4. 问你的领导（他写的 C++ Worker）

祝你开发顺利！🎉
