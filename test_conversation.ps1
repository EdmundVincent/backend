# 多轮对话测试脚本
Write-Host "=== 多轮对话测试 ===" -ForegroundColor Cyan

# 1. 登录获取 Token
Write-Host "`n1. 登录获取 Token..." -ForegroundColor Yellow
$loginResponse = curl.exe -s -X POST http://localhost:8080/api/auth/login `
    -H "Content-Type: application/json" `
    --data-raw '{\"username\":\"admin\",\"password\":\"123456\"}' | ConvertFrom-Json

if ($loginResponse.code -ne 200) {
    Write-Host "❌ 登录失败: $($loginResponse.message)" -ForegroundColor Red
    exit 1
}

$token = $loginResponse.data.token
Write-Host "✅ Token: $($token.Substring(0,30))..." -ForegroundColor Green

# 2. 创建新的 SessionId
$sessionId = [guid]::NewGuid().ToString()
Write-Host "`n2. 创建 Session: $sessionId" -ForegroundColor Yellow

# 3. 第一轮对话
Write-Host "`n3. 第一轮对话: '什么是 Spring WebFlux？'" -ForegroundColor Yellow
$request1 = curl.exe -s -X POST http://localhost:8080/api/rag/answer `
    -H "Authorization: Bearer $token" `
    -H "Content-Type: application/json" `
    --data-raw "{`"question`":`"什么是 Spring WebFlux？`",`"sessionId`":`"$sessionId`"}" | ConvertFrom-Json

Write-Host "   RequestId: $($request1.data.requestId)" -ForegroundColor Green
Write-Host "   Status: $($request1.data.status)" -ForegroundColor Green

# 等待 2 秒
Start-Sleep -Seconds 2

# 4. 第二轮对话
Write-Host "`n4. 第二轮对话: '它有什么优势？'（期待带历史上下文）" -ForegroundColor Yellow
$request2 = curl.exe -s -X POST http://localhost:8080/api/rag/answer `
    -H "Authorization: Bearer $token" `
    -H "Content-Type: application/json" `
    --data-raw "{`"question`":`"它有什么优势？`",`"sessionId`":`"$sessionId`"}" | ConvertFrom-Json

Write-Host "   RequestId: $($request2.data.requestId)" -ForegroundColor Green
Write-Host "   Status: $($request2.data.status)" -ForegroundColor Green

# 5. 查询数据库中的对话历史
Write-Host "`n5. 查询数据库中的对话历史:" -ForegroundColor Yellow
$historyCmd = "SELECT role, LEFT(content, 60) AS content_preview, created_at FROM chat_messages WHERE session_id='$sessionId' ORDER BY created_at;"
docker exec -i ivis-postgres psql -U rag_user -d rag_db -c $historyCmd

# 6. 输出总结
Write-Host "`n=== 测试完成 ===" -ForegroundColor Cyan
Write-Host "SessionId: $sessionId" -ForegroundColor White
Write-Host "Request1: $($request1.data.requestId)" -ForegroundColor White
Write-Host "Request2: $($request2.data.requestId)" -ForegroundColor White
Write-Host "`n等待 RAG Worker 处理完成后，可以通过 Redis 查看结果:" -ForegroundColor Gray
Write-Host "  docker exec -it ivis-redis redis-cli GET rag:result:$($request1.data.requestId)" -ForegroundColor Gray
Write-Host "  docker exec -it ivis-redis redis-cli GET rag:result:$($request2.data.requestId)" -ForegroundColor Gray
