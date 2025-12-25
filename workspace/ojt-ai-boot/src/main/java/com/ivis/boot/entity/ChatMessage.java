package com.ivis.boot.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("chat_messages")
public class ChatMessage {

    @Id
    private UUID id;

    @Column("session_id")
    private UUID sessionId;

    @Column("role")
    private String role; // "user", "assistant", "system"

    @Column("content")
    private String content;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
