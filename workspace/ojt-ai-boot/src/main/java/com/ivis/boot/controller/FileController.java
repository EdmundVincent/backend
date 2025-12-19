package com.ivis.boot.controller;

import com.ivis.component.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final ReactiveKafkaProducerTemplate<String, String> kafkaTemplate;
    private static final String UPLOAD_DIR = System.getProperty("java.io.tmpdir");

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<String>> uploadFile(@RequestPart("file") FilePart filePart) {
        String filename = UUID.randomUUID() + "_" + filePart.filename();
        Path targetPath = Paths.get(UPLOAD_DIR, filename);

        return filePart.transferTo(targetPath)
                .then(Mono.defer(() -> {
                    String message = "File uploaded: " + targetPath.toString();
                    log.info("Sending message to Kafka: {}", message);
                    return kafkaTemplate.send("file-upload-topic", message)
                            .map(senderResult -> {
                                if (senderResult.exception() != null) {
                                    throw new RuntimeException("Kafka send failed", senderResult.exception());
                                }
                                return ApiResponse.success("File uploaded and queued: " + filename);
                            });
                }))
                .onErrorResume(e -> {
                    log.error("Upload failed", e);
                    return Mono.just(ApiResponse.error(500, "Upload failed: " + e.getMessage()));
                });
    }
}
