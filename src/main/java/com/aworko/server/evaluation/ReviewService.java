package com.aworko.server.evaluation;

import static com.aworko.server.evaluation.EvaluationStatus.AWAITING_CONFIRMATION;
import static com.aworko.server.evaluation.EvaluationStatus.AWAITING_REVIEW;
import static com.aworko.server.evaluation.EvaluationStatus.CONFIRMED;
import static com.aworko.server.evaluation.EvaluationStatus.ON_HOLD;

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
import com.aworko.server.organization.User;
import com.aworko.server.organization.UserRepository;
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
 * 팀장(검토자)의 검토. 직원이 이의를 제기하면 백엔드가 검토 요청을 만들어 담당 팀장에게 배정한다(EvaluationService.appeal).
 * 팀장은 직원이 이의를 제기한 평가의 검토만 목록·상세·결정할 수 있다 (EvaluationAccess.hasEmployeeAppeal).
 * keep → 확정, override → 팀장 점수를 새 초안으로 반영하고 확인 대기(직원 본인 확인), hold → 자료 보류.
 * Agent는 검토 결정을 읽지 않으므로 override도 AI 재평가를 보내지 않는다.
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
    private final ReviewRecords reviewRecords;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ReviewSummary> listAssigned(AuthPrincipal me) {
        requireTeamLead(me);
        return reviewRequestRepository.findByReviewerIdOrderByCreatedAtDesc(me.userId()).stream()
                .flatMap(r -> evaluationRepository.findByEvaluationIdAndCompanyId(r.getEvaluationId(), me.companyId())
                        .flatMap(e -> workCaseRepository.findById(e.getWorkId())
                                .filter(w -> access.hasEmployeeAppeal(w, e.getEvaluationId()))
                                .map(w -> summary(r, e, w, nameOf(w.getEmployeeId(), me.companyId()))))
                        .stream())
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewDetail detail(AuthPrincipal me, String reviewRequestId) {
        ReviewRequest review = assigned(me, reviewRequestId);
        Evaluation evaluation = evaluationService.find(me, review.getEvaluationId());
        WorkCase work = access.workOf(evaluation);
        access.requireAppealReviewer(me, work, evaluation.getEvaluationId());
        return new ReviewDetail(review.getReviewRequestId(), review.getTargetItems(), review.getReason(),
                review.getReviewMemo(), latestAppeal(work, evaluation).orElse(null),
                evaluationService.buildDetail(evaluation, work));
    }

    @Transactional
    public StatusResponse resolve(AuthPrincipal me, String reviewRequestId, ResolveReviewRequest request) {
        ReviewRequest review = assigned(me, reviewRequestId);
        Evaluation evaluation = evaluationService.lock(me, review.getEvaluationId());
        WorkCase work = access.workOf(evaluation);
        access.requireAppealReviewer(me, work, evaluation.getEvaluationId());

        EvaluationStatus next = switch (request.decision()) {
            case keep -> CONFIRMED;
            case override -> AWAITING_CONFIRMATION;
            case hold -> ON_HOLD;
        };
        // 초안을 쓰기 전에 상태부터 확인한다(이미 결정된 검토에 새 초안이 생기지 않게)
        if (evaluation.getStatus() != AWAITING_REVIEW) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "현재 상태(" + evaluation.getStatus().getCode() + ")에서는 처리할 수 없습니다");
        }
        Integer draftRevision = null;
        if (request.decision() == ResolveReviewRequest.Decision.override) {
            if (request.newScores() == null || request.newScores().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "수정 요청에는 항목별 점수(new_scores)가 필요합니다");
            }
            draftRevision = reviewRecords.saveReviewerDraft(evaluation, request.newScores(), reviewRequestId,
                    me.userId(), request.note());
        }
        evaluation.transition(Set.of(AWAITING_REVIEW), next);

        ObjectNode payload = evaluationService.payload(evaluation);
        payload.put("review_request_id", reviewRequestId);
        payload.put("decision", request.decision().name());
        payload.put("reason", request.note());
        if (request.newScores() != null) {
            payload.set("new_scores", objectMapper.valueToTree(request.newScores()));
        }
        if (draftRevision != null) {
            payload.put("draft_revision", draftRevision);
        }
        evaluationService.record(work, me, HumanActionType.REVIEW_DECISION, evaluation, payload);
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
                        nameOf(a.getUserId(), work.getCompanyId()), a.getAt()));
    }

    private String nameOf(String userId, String companyId) {
        return userId == null ? null
                : userRepository.findByUserIdAndCompanyId(userId, companyId).map(User::getName).orElse(null);
    }

    private static ReviewSummary summary(ReviewRequest r, Evaluation e, WorkCase work, String employeeName) {
        return new ReviewSummary(r.getReviewRequestId(), e.getEvaluationId(), e.getWorkId(),
                work == null ? null : work.getTitle(),
                work == null || work.getWorkType() == null ? null : work.getWorkType().getCode(),
                work == null || work.getWorkType() == null ? null : work.getWorkType().getLabel(),
                work == null ? null : work.getEmployeeId(), employeeName, r.getTargetItems(), r.getReason(),
                e.getStatus().getCode(), e.getStatus().displayLabel(), r.getCreatedAt());
    }
}
