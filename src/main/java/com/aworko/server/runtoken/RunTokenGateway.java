package com.aworko.server.runtoken;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 평가 건 하나의 최종 평가 실행 토큰(kind=evaluation) 발급. business-mcp /internal/runs가 서명하고,
 * 토큰 안에 발급 시점의 업무 스냅샷(산출물 버전·참조 자료·대화 범위)을 고정한다.
 * 내부 키(X-Internal-Key)와 서명 비밀은 백엔드·MCP만 안다. Worker는 발급된 토큰만 받는다.
 */
public interface RunTokenGateway {

    IssuedToken issue(String evaluationId, Duration ttl);

    record IssuedToken(String token, OffsetDateTime expiresAt) {
    }

    /** MCP가 거절했다(평가 건 없음 등). 그 평가 건만 건너뛴다. code는 MCP 오류 코드 */
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

    /** 연결 실패·시간 초과·응답 형식 오류. 이번 주기의 발급을 멈춘다 */
    class Unavailable extends RuntimeException {

        public Unavailable(String message) {
            super(message);
        }
    }
}
