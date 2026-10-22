package com.aworko.server.work;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 업무 공간 대화(messages). 목표 스키마에서는 llm_exchanges를 펼친 뷰다.
 * 백엔드는 실시간 피드백 범위(seq)를 정할 때만 읽고, 본문(content)은 매핑하지 않는다.
 */
@Entity
@Immutable
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    public static final String ROLE_USER = "user";

    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "work_id", nullable = false)
    private String workId;

    @Column(nullable = false)
    private int seq;

    /** user | assistant */
    @Column(nullable = false)
    private String role;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
