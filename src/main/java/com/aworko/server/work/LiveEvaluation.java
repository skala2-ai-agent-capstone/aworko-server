package com.aworko.server.work;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 실시간 피드백(중간 점검) 요청과 결과. 백엔드는 요청 행(대기)만 만들고,
 * 결과(items·coaching·status)는 Agent가 business-mcp로 채운다.
 */
@Entity
@Table(name = "live_evaluations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveEvaluation {

    @Id
    @Column(name = "live_eval_id")
    private String liveEvalId;

    @Column(name = "work_id", nullable = false)
    private String workId;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "from_seq", nullable = false)
    private int fromSeq;

    @Column(name = "to_seq", nullable = false)
    private int toSeq;

    @Column(name = "rubric_doc_id")
    private String rubricDocId;

    @Column(name = "rubric_version")
    private String rubricVersion;

    @Column(nullable = false)
    private LiveEvaluationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private JsonNode items;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private JsonNode coaching;

    @Column
    private String model;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column
    private String error;

    public static LiveEvaluation request(String workId, String requestedBy, int fromSeq, int toSeq,
                                         String rubricDocId, String rubricVersion, OffsetDateTime now) {
        LiveEvaluation e = new LiveEvaluation();
        e.liveEvalId = "LIVE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        e.workId = workId;
        e.requestedBy = requestedBy;
        e.requestedAt = now;
        e.fromSeq = fromSeq;
        e.toSeq = toSeq;
        e.rubricDocId = rubricDocId;
        e.rubricVersion = rubricVersion;
        e.status = LiveEvaluationStatus.WAITING;
        return e;
    }
}
