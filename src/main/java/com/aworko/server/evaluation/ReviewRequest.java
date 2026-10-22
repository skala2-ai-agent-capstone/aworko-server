package com.aworko.server.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Agent가 business-mcp request_review로 만든 검토 요청. reviewer_id는 담당 팀장. */
@Entity
@Immutable
@Table(name = "review_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewRequest {

    @Id
    @Column(name = "review_request_id")
    private String reviewRequestId;

    @Column(name = "evaluation_id", nullable = false)
    private String evaluationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_items", nullable = false)
    private JsonNode targetItems;

    @Column(nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_memo", nullable = false)
    private JsonNode reviewMemo;

    @Column(name = "reviewer_id")
    private String reviewerId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "run_id")
    private String runId;
}
