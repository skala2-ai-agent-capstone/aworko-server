package com.aworko.server.evaluation;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.organization.UserRole;
import com.aworko.server.work.WorkCase;
import com.aworko.server.work.WorkCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 평가 건 접근 권한 (기획안 2.4·19장, 검토자 = 팀장).
 * - 본인: 자기 업무의 평가
 * - 팀장: 자기 팀 업무 중 자신에게 검토가 배정된 평가만 상세 열람
 * 다른 회사의 평가는 존재 여부를 드러내지 않도록 404로 응답한다.
 */
@Component
@RequiredArgsConstructor
public class EvaluationAccess {

    private final WorkCaseRepository workCaseRepository;
    private final ReviewRequestRepository reviewRequestRepository;

    public WorkCase workOf(Evaluation evaluation) {
        return workCaseRepository.findById(evaluation.getWorkId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "업무를 찾을 수 없습니다"));
    }

    public boolean isOwner(AuthPrincipal me, WorkCase work) {
        return work.getEmployeeId().equals(me.userId());
    }

    public boolean isTeamLeadOf(AuthPrincipal me, WorkCase work) {
        return me.hasRole(UserRole.TEAM_LEAD) && me.teamId() != null && me.teamId().equals(work.getTeamId());
    }

    public boolean isAssignedReviewer(AuthPrincipal me, WorkCase work, String evaluationId) {
        return isTeamLeadOf(me, work) && reviewRequestRepository.findByEvaluationIdOrderByCreatedAt(evaluationId)
                .stream().anyMatch(r -> me.userId().equals(r.getReviewerId()));
    }

    public void requireReadable(AuthPrincipal me, WorkCase work, String evaluationId) {
        if (!isOwner(me, work) && !isAssignedReviewer(me, work, evaluationId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 평가를 볼 권한이 없습니다");
        }
    }

    public void requireOwner(AuthPrincipal me, WorkCase work) {
        if (!isOwner(me, work)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 평가만 처리할 수 있습니다");
        }
    }

    public void requireTeamLead(AuthPrincipal me, WorkCase work) {
        if (!isTeamLeadOf(me, work)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 팀장만 처리할 수 있습니다");
        }
    }
}
