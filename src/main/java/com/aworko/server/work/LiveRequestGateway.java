package com.aworko.server.work;

/**
 * 중간 피드백 요청 행(대기)을 만든다. 범위(최근 완료 질문·응답 최대 5쌍)·유형·현행 기준·마스킹된 대화 스냅샷은
 * business-mcp가 한 트랜잭션에서 고정한다. Worker의 get_messages가 그 스냅샷을 읽으므로 백엔드가 직접 INSERT하지 않는다.
 */
@FunctionalInterface
public interface LiveRequestGateway {

    /** @throws Rejected MCP가 요청을 거절함(완료된 대화 없음, 5분 제한 등) */
    LiveRequest create(String workId, String requestedBy);

    record LiveRequest(String liveEvalId, int fromSeq, int toSeq, String rubricVersion) {
    }

    /** MCP 오류 응답. code는 MCP 오류 코드(VALIDATION_ERROR, NOT_FOUND, RATE_LIMITED, FORBIDDEN …) */
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
