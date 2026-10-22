package com.aworko.server.evaluation;

import static com.aworko.server.evaluation.EvaluationStatus.AWAITING_CONFIRMATION;
import static com.aworko.server.evaluation.EvaluationStatus.CONFIRMED;
import static com.aworko.server.evaluation.EvaluationStatus.FAILED;
import static com.aworko.server.evaluation.EvaluationStatus.ON_HOLD;
import static com.aworko.server.evaluation.EvaluationStatus.WAITING;

import com.aworko.server.evaluation.dto.AppealRequest;
import com.aworko.server.evaluation.dto.EvaluationDetail;
import com.aworko.server.evaluation.dto.EvaluationReport;
import com.aworko.server.evaluation.dto.EvaluationSummary;
import com.aworko.server.evaluation.dto.StatusResponse;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.humanaction.HumanAction;
import com.aworko.server.humanaction.HumanActionRepository;
import com.aworko.server.humanaction.HumanActionType;
import com.aworko.server.messaging.EvaluationFailureRepository;
import com.aworko.server.messaging.OutboxPublisher;
import com.aworko.server.work.WorkCase;
import com.aworko.server.work.WorkCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가 조회와 사람의 행동(본인 확인·이의 제기·재처리). 확정 등 서버 전용 상태는 여기서만 바꾼다.
 * 행동마다 human_actions에 기록하고(business-mcp가 읽음), 멈춘 Agent 실행을 RESUME 메시지로 깨운다.
 */
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final EvaluationRepository evaluationRepository;
    private final EvaluationDraftRepository draftRepository;
    private final EvaluationCoachingRepository coachingRepository;
    private final ProgressLogRepository progressRepository;
    private final ReviewRequestRepository reviewRequestRepository;
    private final WorkCaseRepository workCaseRepository;
    private final HumanActionRepository humanActionRepository;
    private final EvaluationFailureRepository failureRepository;
    private final OutboxPublisher outboxPublisher;
    private final EvaluationAccess access;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<EvaluationSummary> listMine(AuthPrincipal me) {
        List<Evaluation> evaluations = evaluationRepository.findMine(me.companyId(), me.userId());
        Map<String, WorkCase> works = workCaseRepository
                .findAllById(evaluations.stream().map(Evaluation::getWorkId).distinct().toList()).stream()
                .collect(Collectors.toMap(WorkCase::getWorkId, Function.identity()));
        return evaluations.stream().map(e -> EvaluationSummary.of(e, works.get(e.getWorkId()))).toList();
    }

    @Transactional(readOnly = true)
    public EvaluationDetail detail(AuthPrincipal me, String evaluationId) {
        Evaluation evaluation = find(me, evaluationId);
        WorkCase work = access.workOf(evaluation);
        access.requireReadable(me, work, evaluationId);
        return buildDetail(evaluation, work);
    }

    EvaluationDetail buildDetail(Evaluation evaluation, WorkCase work) {
        String id = evaluation.getEvaluationId();
        var draft = draftRepository.findFirstByEvaluationIdOrderByRevisionDesc(id);
        var coaching = coachingRepository.findFirstByEvaluationIdOrderByRevisionDesc(id);
        EvaluationReport report = draft
                .map(d -> EvaluationReportAssembler.assemble(d.getItems(),
                        coaching.map(EvaluationCoaching::getItems).orElse(null)))
                .orElse(EvaluationReport.empty());
        return new EvaluationDetail(EvaluationSummary.of(evaluation, work),
                draft.map(EvaluationDraft::getRevision).orElse(null), report,
                progressRepository.findByEvaluationIdOrderById(id).stream().map(EvaluationDetail.Progress::of).toList(),
                reviewRequestRepository.findByEvaluationIdOrderByCreatedAt(id).stream()
                        .map(EvaluationDetail.Review::of).toList());
    }

    /** 본인 확인: 확인 대기 → 확정 */
    @Transactional
    public StatusResponse confirm(AuthPrincipal me, String evaluationId) {
        Evaluation evaluation = lock(me, evaluationId);
        WorkCase work = access.workOf(evaluation);
        access.requireOwner(me, work);
        evaluation.transition(Set.of(AWAITING_CONFIRMATION), CONFIRMED);
        record(work, me, HumanActionType.CONFIRM, evaluation, payload(evaluation));
        outboxPublisher.resume(evaluationId, HumanActionType.CONFIRM.getCode());
        return StatusResponse.of(evaluation);
    }

    /**
     * 이의 제기: 확인 대기에서만 가능. 상태는 Agent가 근거를 다시 보고 request_review로 '검토 대기'로 바꾼다.
     */
    @Transactional
    public StatusResponse appeal(AuthPrincipal me, String evaluationId, AppealRequest request) {
        Evaluation evaluation = lock(me, evaluationId);
        WorkCase work = access.workOf(evaluation);
        access.requireOwner(me, work);
        if (evaluation.getStatus() != AWAITING_CONFIRMATION) {
            throw new BusinessException(ErrorCode.CONFLICT, "이의 제기는 '확인 대기' 상태에서만 할 수 있습니다");
        }
        ObjectNode payload = payload(evaluation);
        payload.set("target_items", objectMapper.valueToTree(request.targetItems()));
        payload.set("cited_evidence", objectMapper.valueToTree(
                request.citedEvidence() == null ? List.of() : request.citedEvidence()));
        payload.put("comment", request.comment());
        record(work, me, HumanActionType.APPEAL, evaluation, payload);
        outboxPublisher.resume(evaluationId, HumanActionType.APPEAL.getCode());
        return StatusResponse.of(evaluation);
    }

    /** 처리 실패·자료 보류 건 재처리: 담당 팀장만. 대기로 되돌리고 EVALUATE를 다시 보낸다. */
    @Transactional
    public StatusResponse retry(AuthPrincipal me, String evaluationId) {
        Evaluation evaluation = lock(me, evaluationId);
        WorkCase work = access.workOf(evaluation);
        access.requireTeamLead(me, work);
        evaluation.transition(Set.of(FAILED, ON_HOLD), WAITING);
        failureRepository.findByEvaluationIdAndReprocessedAtIsNull(evaluationId)
                .forEach(f -> f.markReprocessed(me.userId()));
        record(work, me, HumanActionType.RETRY_FAILED_EVALUATION, evaluation, payload(evaluation));
        outboxPublisher.evaluate(evaluationId, evaluation.getCompanyId(), evaluation.getWorkId(),
                evaluation.getArtifactVersion(), evaluation.getRubricVersion());
        return StatusResponse.of(evaluation);
    }

    Evaluation find(AuthPrincipal me, String evaluationId) {
        return evaluationRepository.findByEvaluationIdAndCompanyId(evaluationId, me.companyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "평가를 찾을 수 없습니다"));
    }

    Evaluation lock(AuthPrincipal me, String evaluationId) {
        return evaluationRepository.findByIdForUpdate(evaluationId, me.companyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "평가를 찾을 수 없습니다"));
    }

    ObjectNode payload(Evaluation evaluation) {
        return objectMapper.createObjectNode().put("evaluation_id", evaluation.getEvaluationId());
    }

    /** human_actions 기록. payload_masked는 현재 마스킹을 하지 않으므로 원문과 같다 (docs/db-schema.json). */
    void record(WorkCase work, AuthPrincipal me, HumanActionType type, Evaluation evaluation, ObjectNode payload) {
        humanActionRepository.save(HumanAction.record(work.getWorkId(), me.userId(), type,
                evaluation.getArtifactVersion(), payload, payload.deepCopy()));
    }
}
