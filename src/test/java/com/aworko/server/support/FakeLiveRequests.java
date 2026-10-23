package com.aworko.server.support;

import com.aworko.server.work.LiveRequestGateway;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * business-mcp /internal/live-evaluations 대역. 범위는 MCP와 같이 conversation_pairs의 최근 5쌍으로 정한다.
 * 대화 스냅샷·기준 고정은 MCP 쪽 책임이라 여기서는 만들지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeLiveRequests {

    @Bean
    @Primary
    LiveRequestGateway fakeLiveRequestGateway(JdbcTemplate jdbc) {
        return (workId, requestedBy) -> {
            Map<String, Object> range = jdbc.queryForMap("SELECT min(q_seq) AS from_seq, max(a_seq) AS to_seq "
                    + "FROM (SELECT * FROM conversation_pairs(?) ORDER BY q_seq DESC LIMIT 5) t", workId);
            if (range.get("from_seq") == null) {
                throw new LiveRequestGateway.Rejected("VALIDATION_ERROR",
                        "완료된 질문·응답이 아직 없어 중간 피드백을 요청할 수 없습니다");
            }
            String id = "LIVE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            int from = ((Number) range.get("from_seq")).intValue();
            int to = ((Number) range.get("to_seq")).intValue();
            jdbc.update("INSERT INTO live_evaluations (live_eval_id, work_id, requested_by, from_seq, to_seq) "
                    + "VALUES (?, ?, ?, ?, ?)", id, workId, requestedBy, from, to);
            return new LiveRequestGateway.LiveRequest(id, from, to, null);
        };
    }
}
