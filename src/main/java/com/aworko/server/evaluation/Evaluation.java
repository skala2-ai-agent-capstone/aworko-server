package com.aworko.server.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 평가 건. 백엔드는 생성(업무 완료 접수)과 서버 전용 상태 전환만 한다.
 * 진행 상태는 Agent가 business-mcp로 같은 행을 갱신하므로, 상태를 바꿀 때는 항상 DB의 현재 값을 다시 읽는다.
 */
@Entity
@Table(name = "evaluations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Evaluation {

    @Id
    @Column(name = "evaluation_id")
    private String evaluationId;

    @Column(name = "work_id", nullable = false)
    private String workId;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "artifact_id", nullable = false)
    private String artifactId;

    @Column(name = "artifact_version", nullable = false)
    private String artifactVersion;

    @Column(name = "rubric_version", nullable = false)
    private String rubricVersion;

    @Column(nullable = false)
    private EvaluationStatus status;

    /** 업무 완료 이벤트의 중복 방지 키. 기본값 "{work_id}:{artifact_version}:{rubric_version}" */
    @Column(name = "source_key", unique = true)
    private String sourceKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static Evaluation create(String evaluationId, String workId, String companyId, String artifactId,
                                    String artifactVersion, String rubricVersion, String sourceKey) {
        Evaluation e = new Evaluation();
        OffsetDateTime now = OffsetDateTime.now();
        e.evaluationId = evaluationId;
        e.workId = workId;
        e.companyId = companyId;
        e.artifactId = artifactId;
        e.artifactVersion = artifactVersion;
        e.rubricVersion = rubricVersion;
        e.status = EvaluationStatus.WAITING;
        e.sourceKey = sourceKey;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    /** 평가 건 ID 규칙. business-mcp common/runtime.py eval_id_for와 같다. */
    public static String idFor(String workId, String artifactVersion) {
        String base = workId.startsWith("W-") ? workId.substring(2) : workId;
        return "EVAL-" + base + "-" + artifactVersion;
    }

    public static String defaultSourceKey(String workId, String artifactVersion, String rubricVersion) {
        return workId + ":" + artifactVersion + ":" + rubricVersion;
    }
}
