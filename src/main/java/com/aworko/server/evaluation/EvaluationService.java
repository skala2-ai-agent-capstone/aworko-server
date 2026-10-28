package com.aworko.server.evaluation;

import static com.aworko.server.evaluation.EvaluationStatus.AWAITING_CONFIRMATION;
import static com.aworko.server.evaluation.EvaluationStatus.AWAITING_REVIEW;
import static com.aworko.server.evaluation.EvaluationStatus.CONFIRMED;

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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가 조회와 사람의 행동(본인 확인·이의 제기·재처리). 확정 등 서버 전용 상태는 여기서만 바꾼다.
 * 행동마다 human_actions에 기록한다(business-mcp가 읽음). 사람의 행동은 Agent에 메시지로 보내지 않고
 * 이 트랜잭션에서 상태 전환까지 끝낸다(Agent는 EVALUATE만 받는다, ai-agent-capstone docs/backend_worker_alignment.md 3절).
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(EvaluationProperties.class)
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
    private final ReviewRecords reviewRecords;
    private final EvaluationAccess access;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final EvaluationProperties props;
    private final Clock clock;

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
        return StatusResponse.of(evaluation);
    }

    /**
     * 이의 제기: 확인 대기에서만 가능. 이의 항목으로 검토 요청을 만들어 담당 팀장에게 배정하고 '검토 대기'로 바꾼다.
     */
    @Transactional
    public StatusResponse appeal(AuthPrincipal me, String evaluationId, AppealRequest request) {
        Evaluation evaluation = lock(me, evaluationId);
        WorkCase work = access.workOf(evaluation);
        access.requireOwner(me, work);
        if (evaluation.getStatus() != AWAITING_CONFIRMATION) {
            throw new BusinessException(ErrorCode.CONFLICT, "이의 제기는 '확인 대기' 상태에서만 할 수 있습니다");
        }
        String reviewRequestId = reviewRecords.createAppealReview(evaluation, work, request.targetItems(),
                request.citedEvidence());
        evaluation.transition(Set.of(AWAITING_CONFIRMATION), AWAITING_REVIEW);
        ObjectNode payload = payload(evaluation);
        payload.set("target_items", objectMapper.valueToTree(request.targetItems()));
        payload.set("cited_evidence", objectMapper.valueToTree(
                request.citedEvidence() == null ? List.of() : request.citedEvidence()));
        payload.put("comment", request.comment());
        payload.put("review_request_id", reviewRequestId);
        record(work, me, HumanActionType.APPEAL, evaluation, payload);
        return StatusResponse.of(evaluation);
    }

    /**
     * 재처리: 담당 팀장만. 처리 실패·자료 보류 건, 또는 대기에 들어간 뒤 staleAfter가 지나도록 '평가 중'인 건을
     * 대기로 되돌리고 EVALUATE를 새 message_id로 다시 보낸다. Agent는 message_id가 다르면 새 실행으로 보고 다시 평가해
     * 새 판을 저장한다. Agent가 진행 단계를 기록하지 않아 처리 중인 건도 staleAfter가 지나면 받으며, 그때는 먼저 끝난
     * 실행의 결과가 남는다. 업무의 최신 평가만 받는다(그 뒤 새 버전으로 다시 완료했으면 이전 평가는 돌리지 않는다).
     */
    @Transactional
    public StatusResponse retry(AuthPrincipal me, String evaluationId) {
        // 재완료(WorkEventService.complete, FOR UPDATE)와 같은 순서로 업무 행부터 잠가 한 업무에 평가가 둘 진행되지
        // 않게 한다. NO KEY UPDATE라 본인 확인·검토 결정의 human_actions FK 검사(KEY SHARE)와는 엇갈려 교착되지 않는다
        String workId = jdbc.query("SELECT work_id FROM evaluations WHERE evaluation_id = ? AND company_id = ?",
                        (rs, i) -> rs.getString(1), evaluationId, me.companyId()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "평가를 찾을 수 없습니다"));
        jdbc.queryForList("SELECT 1 FROM work_cases WHERE work_id = ? FOR NO KEY UPDATE", workId);
        Evaluation evaluation = lock(me, evaluationId);
        WorkCase work = access.workOf(evaluation);
        access.requireTeamLead(me, work);
        String latest = jdbc.queryForObject("SELECT evaluation_id FROM evaluations WHERE work_id = ? "
                + "ORDER BY created_at DESC, evaluation_id DESC LIMIT 1", String.class, workId);
        if (!evaluationId.equals(latest)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이 업무에 더 최근 평가가 있어 이전 평가는 재처리할 수 없습니다");
        }
        evaluation.resetForRetry(OffsetDateTime.now(clock).minus(props.staleAfter()));
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
