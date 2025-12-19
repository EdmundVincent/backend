# 后端开发进度报告

## 1. 概览
本项目已完成核心后端功能的开发，包括基于 Redis 的 JWT 认证机制和基于 Kafka 的异步文件上传处理。所有代码注释已翻译为日语。

## 2. 已完成功能

### 2.1 认证模块 (Authentication)
- **功能**: 实现了基于 JWT (JSON Web Token) 的用户登录与认证。
- **机制**: 
  - 用户登录成功后生成 JWT。
  - Token 被存储在 Redis 中（Key: `auth:token:{username}`），有效期设置为 1 小时。
  - 每次请求时，过滤器 (`JwtWebFilter`) 会验证 Token 签名并检查其是否存在于 Redis 中，实现了服务端的 Token 管理（可注销/黑名单）。
- **API**:
  - `POST /api/auth/login`
    - **请求**: `{"username": "admin", "password": "..."}`
    - **响应**: `{"token": "eyJ...", "username": "admin"}`

### 2.2 文件上传模块 (File Upload)
- **功能**: 实现了文件上传接口，并将上传事件发送至消息队列。
- **机制**:
  - 接收 `multipart/form-data` 格式的文件。
  - 文件暂时保存至服务器临时目录。
  - 上传成功后，向 Kafka 主题 `file-upload-topic` 发送消息（包含文件路径），供后续 AI 处理服务消费。
- **API**:
  - `POST /api/file/upload`
    - **Header**: `Authorization: Bearer <token>`
    - **Body**: `form-data` (key: `file`, value: <文件对象>)
    - **响应**: `{"code": 200, "message": "Success", "data": "File uploaded and queued..."}`

### 2.3 基础设施 (Infrastructure)
- **Docker 环境**: 
  - 修复并优化了 `docker-compose.yml`。
  - 成功运行了 Redis, Kafka, Zookeeper, PostgreSQL, MinIO 等服务。
  - 应用容器 (`ivis-rag-api`) 已配置为连接 Docker 网络内的服务。

## 3. 前端开发指南

### 3.1 认证流程
1.  调用登录接口获取 `token`。
2.  后续所有受保护的 API 请求，请在 Header 中携带：
    ```
    Authorization: Bearer <token>
    ```

### 3.2 文件上传
- 请使用 `FormData` 对象上传文件。
- 示例代码 (JavaScript):
  ```javascript
  const formData = new FormData();
  formData.append('file', fileInput.files[0]);

  fetch('/api/file/upload', {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer ' + token
    },
    body: formData
  });
  ```

