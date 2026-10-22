package com.aworko.server.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** DLQ로 간 평가 메시지 보존. V1__backend_tables.sql */
@Entity
@Table(name = "evaluation_failures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evaluation_id", nullable = false)
    private String evaluationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode message;

    @Column
    private String reason;

    @Column(name = "failed_at", nullable = false)
    private OffsetDateTime failedAt;

    @Column(name = "reprocessed_at")
    private OffsetDateTime reprocessedAt;

    @Column(name = "reprocessed_by")
    private String reprocessedBy;

    public static EvaluationFailure of(String evaluationId, JsonNode message, String reason) {
        EvaluationFailure f = new EvaluationFailure();
        f.evaluationId = evaluationId;
        f.message = message;
        f.reason = reason;
        f.failedAt = OffsetDateTime.now();
        return f;
    }

    public void markReprocessed(String userId) {
        this.reprocessedAt = OffsetDateTime.now();
        this.reprocessedBy = userId;
    }
}
