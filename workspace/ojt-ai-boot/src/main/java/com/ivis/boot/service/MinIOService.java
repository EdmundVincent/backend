package com.ivis.boot.service;

import io.minio.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinIOService {

    private final MinioClient minioClient;
    private final com.ivis.boot.config.MinIOConfig minIOConfig;

    /**
     * 应用启动时初始化 bucket，避免每次请求都检查
     */
    @PostConstruct
    public void initBucket() {
        try {
            boolean found = minioClient.bucketExists(
                BucketExistsArgs.builder()
                    .bucket(minIOConfig.getBucket())
                    .build()
            );
            
            if (!found) {
                minioClient.makeBucket(
                    MakeBucketArgs.builder()
                        .bucket(minIOConfig.getBucket())
                        .build()
                );
                log.info("MinIO bucket created at startup: {}", minIOConfig.getBucket());
            } else {
                log.info("MinIO bucket already exists: {}", minIOConfig.getBucket());
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket at startup", e);
            // 启动时失败不抛异常，后续上传时会再次尝试
        }
    }

    /**
     * 上传文件到 MinIO（非阻塞实现）
     * @param filePart WebFlux 文件部分
     * @param objectName MinIO 对象名称（包含路径，例如: "tenant-001/kb-001/uuid.pdf"）
     * @return MinIO 对象的 S3 路径
     */
    public Mono<String> uploadFile(FilePart filePart, String objectName) {
        return Mono.defer(() -> {
            try {
                // 在弹性线程池中创建临时文件
                Path tempFile = Files.createTempFile("upload-", filePart.filename());
                
                // 使用 DataBufferUtils 进行非阻塞文件写入
                return DataBufferUtils.write(filePart.content(), tempFile, StandardOpenOption.WRITE)
                    .then(Mono.fromCallable(() -> {
                        // 阻塞操作在 boundedElastic 调度器中执行
                        try (InputStream inputStream = Files.newInputStream(tempFile)) {
                            minioClient.putObject(
                                PutObjectArgs.builder()
                                    .bucket(minIOConfig.getBucket())
                                    .object(objectName)
                                    .stream(inputStream, Files.size(tempFile), -1)
                                    .contentType(getContentType(filePart.filename()))
                                    .build()
                            );
                        }
                        
                        String s3Path = String.format("s3://%s/%s", minIOConfig.getBucket(), objectName);
                        log.info("File uploaded successfully to MinIO: {}", s3Path);
                        return s3Path;
                    })
                    .subscribeOn(Schedulers.boundedElastic()))  // 阻塞I/O在弹性线程池执行
                    .doFinally(signal -> {
                        // 清理临时文件
                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (Exception e) {
                            log.warn("Failed to delete temp file: {}", tempFile, e);
                        }
                    });
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }

    /**
     * 根据文件名推断 Content-Type
     */
    private String getContentType(String filename) {
        String lowerFilename = filename.toLowerCase();
        if (lowerFilename.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lowerFilename.endsWith(".txt")) {
            return "text/plain";
        } else if (lowerFilename.endsWith(".doc") || lowerFilename.endsWith(".docx")) {
            return "application/msword";
        } else if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerFilename.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }
}
