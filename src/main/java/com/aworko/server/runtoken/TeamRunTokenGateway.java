package com.aworko.server.runtoken;

import java.time.Duration;

/**
 * 팀 평가 요청 하나의 실행 토큰(kind=team) 발급. business-mcp /internal/team-runs가 서명하고,
 * 토큰은 그 요청의 고정 스냅샷({request_id, snapshot_id, snapshot_sha256})에만 쓸 수 있다.
 * 다시 발급해도 스냅샷은 그대로라 만료 전 재발급이 안전하다. 오류는 RunTokenGateway.Rejected·Unavailable을 쓴다.
 */
@FunctionalInterface
public interface TeamRunTokenGateway {

    RunTokenGateway.IssuedToken issueTeam(String requestId, Duration ttl);
}
