package com.aworko.server.aggregation;

import java.time.LocalDate;

/**
 * 팀 평가 요청 만들기. business-mcp /internal/team-evaluations가 그 자리에서 확정 평가를 집계해 스냅샷으로 고정한다
 * (업무별 최신 확정 1건, 업무 유형·기준 버전별, 최소 인원 미달 그룹 제외, 공개 승인이 아니면 groups=[]).
 * 집계는 정본 DB를 읽는 MCP 몫이라 백엔드가 직접 쓰지 않는다.
 */
public interface TeamEvaluationGateway {

    /**
     * @param periodEnd 미포함(MCP 계약). 화면의 기간 끝 날짜 + 1일
     * @param requestId 백엔드가 만든 UUID. 같은 ID·같은 범위로 다시 부르면 기존 요청을 돌려준다
     */
    Created create(String companyId, String teamId, LocalDate periodStart, LocalDate periodEnd, boolean releaseApproved,
                   String requestId, String requestedBy);

    record Created(String requestId, boolean releaseApproved, int minMembers, boolean created) {
    }

    /** MCP 오류 응답. code는 MCP 오류 코드(VALIDATION_ERROR, NOT_FOUND, VERSION_CONFLICT, TEMPORARY_FAILURE …) */
    class Rejected extends RuntimeException {

        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
