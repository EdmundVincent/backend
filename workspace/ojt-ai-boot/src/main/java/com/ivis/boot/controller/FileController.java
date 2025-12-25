package com.ivis.boot.controller;

import com.ivis.boot.dto.file.FileUploadResponse;
import com.ivis.boot.service.MinIOService;
import com.ivis.boot.service.RagKafkaProducerService;
import com.ivis.component.auth.JwtUtil;
import com.ivis.component.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 文件上传 Controller
 * API1: 文件上传 → MinIO → Kafka doc_ingest
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final MinIOService minioService;
    private final RagKafkaProducerService kafkaProducerService;
    private final JwtUtil jwtUtil;

    // 允许上传的文件类型白名单
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
        ".pdf", ".txt", ".doc", ".docx", ".md", ".json", ".csv", ".xlsx", ".xls"
    );
    
    // 最大文件大小 (100MB)
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    /**
     * 文件上传接口
     * POST /api/file/upload
     * 
     * @param filePart 文件
     * @param tenantId 租户ID（可选，默认从token获取username）
     * @param kbId 知识库ID（可选，默认 kb-001）
     * @param authHeader JWT Token
     * @return 文档ID和状态
     */
    @PreAuthorize("permitAll()")
    @PostMapping("/upload")
    public Mono<ApiResponse<FileUploadResponse>> uploadFile(
            @RequestPart("file") FilePart filePart,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "kbId", defaultValue = "kb-001") String kbId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        
        log.info("File upload request received - filePart: {}, kbId: {}", 
            filePart != null ? filePart.filename() : "null", kbId);
        
        // 验证文件是否存在
        if (filePart == null) {
            log.error("File part is null");
            return Mono.just(ApiResponse.error(400, "File is required"));
        }
        
        // 验证文件类型和文件名安全性
        String filename = filePart.filename();
        String validationError = validateFile(filename);
        if (validationError != null) {
            log.warn("File validation failed: {}", validationError);
            return Mono.just(ApiResponse.error(400, validationError));
        }
        
        // 从 Token 提取用户名作为默认租户ID
        String username = extractUsername(authHeader);
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "tenant-" + username;
        }
        
        // 生成文档ID
        String docId = UUID.randomUUID().toString();
        
        log.info("Uploading file: docId={}, filename={}, tenant={}, kb={}", 
            docId, filename, tenantId, kbId);
        
        // MinIO 对象名称: tenant/kb/docId.extension
        String extension = getFileExtension(filename);
        String objectName = String.format("%s/%s/%s%s", tenantId, kbId, docId, extension);
        
        String finalTenantId = tenantId;
        
        return minioService.uploadFile(filePart, objectName)
            .flatMap(s3Path -> {
                log.info("File uploaded to MinIO: {}", s3Path);
                // 发送文档摄取事件到 Kafka
                return kafkaProducerService.sendDocIngestEvent(
                    docId, finalTenantId, kbId, s3Path, filename
                );
            })
            .then(Mono.just(ApiResponse.success(
                new FileUploadResponse(docId, "processing", 
                    "File uploaded and queued for processing")
            )))
            .onErrorResume(e -> {
                log.error("File upload failed: {}", e.getMessage(), e);
                return Mono.just(ApiResponse.error(500, 
                    "File upload failed: " + e.getMessage()));
            });
    }

    /**
     * 从 Authorization header 提取用户名
     */
    private String extractUsername(String authHeader) {
        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                return jwtUtil.extractUsername(token);
            }
        } catch (Exception e) {
            log.warn("Failed to extract username from token", e);
        }
        return "anonymous";
    }

    /**
     * 验证文件安全性
     * @param filename 文件名
     * @return 错误信息，如果验证通过返回 null
     */
    private String validateFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return "Filename is required";
        }
        
        // 防止路径遍历攻击
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return "Invalid filename: path traversal not allowed";
        }
        
        // 检查文件类型白名单
        String extension = getFileExtension(filename).toLowerCase();
        if (extension.isEmpty()) {
            return "File must have an extension";
        }
        
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return "File type not allowed: " + extension + ". Allowed types: " + ALLOWED_EXTENSIONS;
        }
        
        return null; // 验证通过
    }

    /**
     * 获取文件扩展名（包含点号）
     */
    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot);
        }
        return "";
    }
}
