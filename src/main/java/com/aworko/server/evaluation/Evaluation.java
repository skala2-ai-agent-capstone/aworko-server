package com.aworko.server.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Set;
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

    /**
     * 서버 전용 상태 전이. 현재 상태가 from에 없으면 409(이미 처리됐거나 아직 처리할 수 없는 상태).
     * 호출 전에 행을 잠가(findByIdForUpdate) Agent의 진행 상태 갱신과 겹치지 않게 한다.
     */
    public void transition(Set<EvaluationStatus> from, EvaluationStatus to) {
        if (!from.contains(status)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "현재 상태(" + status.getCode() + ")에서는 처리할 수 없습니다");
        }
        status = to;
        updatedAt = OffsetDateTime.now();
    }

    /**
     * DLQ로 간 평가를 처리 실패로 표시한다. Agent가 처리 중인 상태(대기·진행 4단계)에서만 바꾼다.
     * 확인 대기·검토 대기·자료 보류·확정은 결과가 이미 저장된 뒤라, 늦게 다시 온 메시지의 실패로 덮어쓰지 않는다.
     * 호출 전에 행을 잠근다(findByIdForUpdate).
     * @return 상태를 바꿨으면 true
     */
    public boolean markFailed() {
        if (!EvaluationStatus.IN_PROGRESS.contains(status)) {
            return false;
        }
        status = EvaluationStatus.FAILED;
        updatedAt = OffsetDateTime.now();
        return true;
    }

    /**
     * 팀장 재처리로 대기로 되돌린다. 처리 실패·자료 보류이거나, staleBefore보다 전부터 처리 중에 멈춘 건만 받는다
     * (메시지를 잃었거나 Worker가 멈춘 경우의 탈출구). 호출 전에 행을 잠근다.
     */
    public void resetForRetry(OffsetDateTime staleBefore) {
        boolean stuck = EvaluationStatus.IN_PROGRESS.contains(status) && updatedAt.isBefore(staleBefore);
        if (status != EvaluationStatus.FAILED && status != EvaluationStatus.ON_HOLD && !stuck) {
            throw new BusinessException(ErrorCode.CONFLICT, EvaluationStatus.IN_PROGRESS.contains(status)
                    ? "평가가 진행 중입니다. 오래 멈춰 있을 때만 재처리할 수 있습니다"
                    : "현재 상태(" + status.getCode() + ")에서는 처리할 수 없습니다");
        }
        status = EvaluationStatus.WAITING;
        updatedAt = OffsetDateTime.now();
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
