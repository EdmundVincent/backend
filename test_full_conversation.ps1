# 完整的多轮对话测试脚本
# 使用统一的 SessionId

Write-Host "=== Spring Boot API 连接测试 ===" -ForegroundColor Cyan
$retries = 0
$maxRetries = 20
while ($retries -lt $maxRetries) {
    try {
        $health = curl.exe -s http://localhost:8080/api/public/config | ConvertFrom-Json
        if ($health.code -eq 200) {
            Write-Host "✅ API 已就绪" -ForegroundColor Green
            break
        }
    } catch {}
    $retries++
    Write-Host "等待 API 启动... ($retries/$maxRetries)" -ForegroundColor Yellow
    Start-Sleep -Seconds 3
}

if ($retries -eq $maxRetries) {
    Write-Host "❌ API 启动超时" -ForegroundColor Red
    exit 1
}

# 1. 登录
Write-Host "`n=== 1. 登录获取 Token ===" -ForegroundColor Cyan
$loginResp = curl.exe -s -X POST http://localhost:8080/api/auth/login `
    -H "Content-Type: application/json" `
    --data-raw '{\"username\":\"admin\",\"password\":\"123456\"}' | ConvertFrom-Json
$token = $loginResp.data.token
Write-Host "Token: $($token.Substring(0,30))..." -ForegroundColor Green

# 2. 使用固定的 SessionId
$sessionId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
Write-Host "`n=== 2. 使用 SessionId: $sessionId ===" -ForegroundColor Cyan

# 3. 第一轮对话
Write-Host "`n=== 3. 第一轮对话: '什么是 Spring WebFlux？' ===" -ForegroundColor Cyan
$q1 = @{ question = "什么是 Spring WebFlux？"; sessionId = $sessionId } | ConvertTo-Json
$q1 | Out-File -Encoding UTF8 question1.json

$r1 = curl.exe -s -X POST http://localhost:8080/api/rag/answer `
    -H "Authorization: Bearer $token" `
    -H "Content-Type: application/json" `
    -d "@question1.json" | ConvertFrom-Json

if ($r1.code -ne 200) {
    Write-Host "❌ 第一轮请求失败: $($r1.message)" -ForegroundColor Red
    exit 1
}

Write-Host "✅ RequestId: $($r1.data.requestId)" -ForegroundColor Green
Write-Host "   Status: $($r1.data.status)" -ForegroundColor Gray

# 等待3秒
Start-Sleep -Seconds 3

# 4. 第二轮对话（追问，期待历史上下文）
Write-Host "`n=== 4. 第二轮对话: '它有什么优势？' ===" -ForegroundColor Cyan
$q2 = @{ question = "它有什么优势？"; sessionId = $sessionId } | ConvertTo-Json
$q2 | Out-File -Encoding UTF8 question2.json

$r2 = curl.exe -s -X POST http://localhost:8080/api/rag/answer `
    -H "Authorization: Bearer $token" `
    -H "Content-Type: application/json" `
    -d "@question2.json" | ConvertFrom-Json

if ($r2.code -ne 200) {
    Write-Host "❌ 第二轮请求失败: $($r2.message)" -ForegroundColor Red
    exit 1
}

Write-Host "✅ RequestId: $($r2.data.requestId)" -ForegroundColor Green
Write-Host "   Status: $($r2.data.status)" -ForegroundColor Gray

# 5. 查询数据库中的Session
Write-Host "`n=== 5. 查询数据库中的 Session ===" -ForegroundColor Cyan
docker exec -i ivis-postgres psql -U rag_user -d rag_db -c `
    "SELECT id, title, created_at FROM sessions WHERE id='$sessionId';"

# 6. 查询对话历史
Write-Host "`n=== 6. 查询对话历史 ===" -ForegroundColor Cyan
docker exec -i ivis-postgres psql -U rag_user -d rag_db -c `
    "SELECT role, LEFT(content, 50) AS content, created_at FROM chat_messages WHERE session_id='$sessionId' ORDER BY created_at;"

Write-Host "`n=== 测试完成 ===" -ForegroundColor Cyan
Write-Host "SessionId: $sessionId" -ForegroundColor White
Write-Host "Request1:  $($r1.data.requestId)" -ForegroundColor White
Write-Host "Request2:  $($r2.data.requestId)" -ForegroundColor White
Write-Host "`n提示: 等待 RAG Worker 处理后，可通过以下命令查看结果：" -ForegroundColor Gray
Write-Host "  curl -H \"Authorization: Bearer \$token\" http://localhost:8080/api/rag/result/$($r2.data.requestId)" -ForegroundColor Gray

# 清理临时文件
Remove-Item question1.json, question2.json -ErrorAction SilentlyContinue
