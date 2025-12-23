package com.ivis.boot.service;

import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinIOService {

    private final MinioClient minioClient;
    private final com.ivis.boot.config.MinIOConfig minIOConfig;

    /**
     * 上传文件到 MinIO
     * @param filePart WebFlux 文件部分
     * @param objectName MinIO 对象名称（包含路径，例如: "tenant-001/kb-001/uuid.pdf"）
     * @return MinIO 对象的 S3 路径
     */
    public Mono<String> uploadFile(FilePart filePart, String objectName) {
        return Mono.fromCallable(() -> {
            // 确保 bucket 存在
            ensureBucketExists();

            // 创建临时文件
            Path tempFile = Files.createTempFile("upload-", filePart.filename());
            
            try {
                // 保存文件到临时位置
                filePart.transferTo(tempFile).block();
                
                // 上传到 MinIO
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
                
                // 返回 S3 路径
                String s3Path = String.format("s3://%s/%s", minIOConfig.getBucket(), objectName);
                log.info("File uploaded successfully to MinIO: {}", s3Path);
                return s3Path;
                
            } finally {
                // 清理临时文件
                Files.deleteIfExists(tempFile);
            }
        });
    }

    /**
     * 确保 bucket 存在，不存在则创建
     */
    private void ensureBucketExists() throws Exception {
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
            log.info("MinIO bucket created: {}", minIOConfig.getBucket());
        }
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
