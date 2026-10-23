package com.aworko.server.work;

import com.aworko.server.document.Document;
import com.aworko.server.document.DocumentRepository;
import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.messaging.OutboxPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업무 완료 접수 → 평가 건 생성 + EVALUATE 발행(아웃박스). 평가 완료까지 기다리지 않는다(202).
 * 같은 (업무, 산출물 버전, 루브릭 버전)은 평가 건을 한 번만 만든다 (business-mcp create_evaluation과 같은 규칙).
 * 업무를 완료 상태로 바꾸고 work.completed 이벤트를 남긴다. business-mcp는 이 이벤트 시각을 그 버전의 완료 시각으로
 * 읽는다(견적서 평가가 적용할 가격표를 고를 때 쓴다).
 */
@Service
@RequiredArgsConstructor
public class WorkEventService {

    private final WorkCaseRepository workCaseRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final DocumentRepository documentRepository;
    private final EvaluationRepository evaluationRepository;
    private final OutboxPublisher outboxPublisher;
    private final JdbcTemplate jdbc;

    @Transactional
    public WorkEventResponse complete(AuthPrincipal me, WorkEventRequest request) {
        WorkCase work = workCaseRepository.findByWorkIdAndCompanyId(request.taskId(), me.companyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "업무를 찾을 수 없습니다"));
        if (!work.getEmployeeId().equals(me.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 업무만 완료할 수 있습니다");
        }
        if (work.getWorkType() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "업무 유형이 정해지지 않아 평가를 시작할 수 없습니다");
        }
        ArtifactVersion artifact = artifactVersionRepository
                .findByWorkIdAndVersion(work.getWorkId(), request.deliverableVersion()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "해당 버전의 산출물이 없습니다"));
        Document rubric = currentRubric(me.companyId(), work.getWorkType());

        String sourceKey = request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                ? request.idempotencyKey()
                : Evaluation.defaultSourceKey(work.getWorkId(), artifact.getVersion(), rubric.getVersion());
        var existing = evaluationRepository.findBySourceKey(sourceKey);
        if (existing.isPresent()) {
            return new WorkEventResponse(existing.get().getEvaluationId(), false,
                    existing.get().getStatus().getCode());
        }

        String evaluationId = Evaluation.idFor(work.getWorkId(), artifact.getVersion());
        if (evaluationRepository.existsById(evaluationId)) {
            evaluationId = evaluationId + "-" + rubric.getVersion();
        }
        Evaluation evaluation = evaluationRepository.save(Evaluation.create(evaluationId, work.getWorkId(),
                me.companyId(), artifact.getArtifactId(), artifact.getVersion(), rubric.getVersion(), sourceKey));
        jdbc.update("UPDATE work_cases SET status = ?, completed_at = now() WHERE work_id = ?",
                WorkRows.STATUS_COMPLETED, work.getWorkId());
        jdbc.update("INSERT INTO work_events (event_id, work_id, event_type, artifact_version, rubric_version, "
                        + "idempotency_key, occurred_at) VALUES (?, ?, 'work.completed', ?, ?, ?, now())",
                "EV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), work.getWorkId(),
                artifact.getVersion(), rubric.getVersion(), sourceKey);
        outboxPublisher.evaluate(evaluation.getEvaluationId(), me.companyId(), work.getWorkId(),
                artifact.getVersion(), rubric.getVersion());
        return new WorkEventResponse(evaluation.getEvaluationId(), true, evaluation.getStatus().getCode());
    }

    private Document currentRubric(String companyId, WorkType workType) {
        return documentRepository.findByCompanyIdAndCollectionAndWorkTypeAndStatusOrderByEffectiveFromDesc(
                        companyId, Document.COLLECTION_RUBRICS, workType, Document.STATUS_CURRENT)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "현행 루브릭이 없어 평가를 시작할 수 없습니다"));
    }
}
