package com.aworko.server.evaluation;

import static com.aworko.server.evaluation.EvaluationStatus.AWAITING_REVIEW;
import static com.aworko.server.evaluation.EvaluationStatus.CONFIRMED;
import static com.aworko.server.evaluation.EvaluationStatus.ON_HOLD;
import static com.aworko.server.evaluation.EvaluationStatus.WAITING;

import com.aworko.server.evaluation.dto.ResolveReviewRequest;
import com.aworko.server.evaluation.dto.ReviewDetail;
import com.aworko.server.evaluation.dto.ReviewSummary;
import com.aworko.server.evaluation.dto.StatusResponse;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.humanaction.HumanAction;
import com.aworko.server.humanaction.HumanActionRepository;
import com.aworko.server.humanaction.HumanActionType;
import com.aworko.server.messaging.OutboxPublisher;
import com.aworko.server.organization.UserRole;
import com.aworko.server.work.WorkCase;
import com.aworko.server.work.WorkCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀장(검토자)의 검토. 이의 제기나 채점 불일치로 Agent가 request_review를 하면 business-mcp가 담당 팀장을 배정한다.
 * keep → 확정, override → 재평가(RESUME review_decision), hold → 자료 보류.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRequestRepository reviewRequestRepository;
    private final EvaluationRepository evaluationRepository;
    private final WorkCaseRepository workCaseRepository;
    private final HumanActionRepository humanActionRepository;
    private final EvaluationService evaluationService;
    private final EvaluationAccess access;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ReviewSummary> listAssigned(AuthPrincipal me) {
        requireTeamLead(me);
        return reviewRequestRepository.findByReviewerIdOrderByCreatedAtDesc(me.userId()).stream()
                .flatMap(r -> evaluationRepository.findByEvaluationIdAndCompanyId(r.getEvaluationId(), me.companyId())
                        .map(e -> summary(r, e, workCaseRepository.findById(e.getWorkId()).orElse(null))).stream())
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewDetail detail(AuthPrincipal me, String reviewRequestId) {
        ReviewRequest review = assigned(me, reviewRequestId);
        Evaluation evaluation = evaluationService.find(me, review.getEvaluationId());
        WorkCase work = access.workOf(evaluation);
        access.requireTeamLead(me, work);
        return new ReviewDetail(review.getReviewRequestId(), review.getTargetItems(), review.getReason(),
                review.getReviewMemo(), latestAppeal(work, evaluation).orElse(null),
                evaluationService.buildDetail(evaluation, work));
    }

    @Transactional
    public StatusResponse resolve(AuthPrincipal me, String reviewRequestId, ResolveReviewRequest request) {
        ReviewRequest review = assigned(me, reviewRequestId);
        Evaluation evaluation = evaluationService.lock(me, review.getEvaluationId());
        WorkCase work = access.workOf(evaluation);
        access.requireTeamLead(me, work);

        EvaluationStatus next = switch (request.decision()) {
            case keep -> CONFIRMED;
            case override -> WAITING;
            case hold -> ON_HOLD;
        };
        evaluation.transition(Set.of(AWAITING_REVIEW), next);

        ObjectNode payload = evaluationService.payload(evaluation);
        payload.put("review_request_id", reviewRequestId);
        payload.put("decision", request.decision().name());
        payload.put("reason", request.note());
        if (request.newScores() != null) {
            payload.set("new_scores", objectMapper.valueToTree(request.newScores()));
        }
        evaluationService.record(work, me, HumanActionType.REVIEW_DECISION, evaluation, payload);
        if (request.decision() == ResolveReviewRequest.Decision.override) {
            outboxPublisher.resume(evaluation.getEvaluationId(), HumanActionType.REVIEW_DECISION.getCode());
        }
        return StatusResponse.of(evaluation);
    }

    private ReviewRequest assigned(AuthPrincipal me, String reviewRequestId) {
        requireTeamLead(me);
        ReviewRequest review = reviewRequestRepository.findById(reviewRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "검토 요청을 찾을 수 없습니다"));
        if (!me.userId().equals(review.getReviewerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인에게 배정된 검토만 처리할 수 있습니다");
        }
        return review;
    }

    private static void requireTeamLead(AuthPrincipal me) {
        if (!me.hasRole(UserRole.TEAM_LEAD)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "팀장만 검토할 수 있습니다");
        }
    }

    /** 이 평가에 대한 가장 최근 이의 제기. payload.evaluation_id가 없는 예전 기록은 같은 업무의 것으로 본다. */
    private Optional<ReviewDetail.Appeal> latestAppeal(WorkCase work, Evaluation evaluation) {
        return humanActionRepository.findByWorkIdAndActionOrderByAt(work.getWorkId(), HumanActionType.APPEAL.getCode())
                .stream()
                .filter(a -> !a.getPayload().hasNonNull("evaluation_id")
                        || a.getPayload().get("evaluation_id").asText().equals(evaluation.getEvaluationId()))
                .max(Comparator.comparing(HumanAction::getAt))
                .map(a -> new ReviewDetail.Appeal(a.getPayload().path("comment").asText(null),
                        a.getPayload().get("target_items"), a.getPayload().get("cited_evidence"), a.getUserId(),
                        a.getAt()));
    }

    private static ReviewSummary summary(ReviewRequest r, Evaluation e, WorkCase work) {
        return new ReviewSummary(r.getReviewRequestId(), e.getEvaluationId(), e.getWorkId(),
                work == null ? null : work.getTitle(),
                work == null || work.getWorkType() == null ? null : work.getWorkType().getCode(),
                work == null ? null : work.getEmployeeId(), r.getTargetItems(), r.getReason(),
                e.getStatus().getCode(), e.getStatus().displayLabel(), r.getCreatedAt());
    }
}
