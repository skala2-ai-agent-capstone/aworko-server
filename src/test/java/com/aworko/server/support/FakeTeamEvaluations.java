package com.aworko.server.support;

import com.aworko.server.aggregation.TeamEvaluationGateway;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * business-mcp /internal/team-evaluations 대역. 받은 요청을 기억하고 team_evaluations에 대기 행을 넣는다(스냅샷은 최소 모양).
 * 실제 집계·공개 기준 적용은 MCP 쪽 책임이라 여기서는 하지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeTeamEvaluations {

    @Bean
    @Primary
    Recorder fakeTeamEvaluationGateway(JdbcTemplate jdbc) {
        return new Recorder(jdbc);
    }

    public record Call(String companyId, String teamId, LocalDate periodStart, LocalDate periodEnd,
                       boolean releaseApproved, String requestId, String requestedBy) {
    }

    public static class Recorder implements TeamEvaluationGateway {

        public final List<Call> calls = new CopyOnWriteArrayList<>();
        private final JdbcTemplate jdbc;
        private Rejected next;

        Recorder(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** 다음 한 번은 이 MCP 오류로 거절한다 */
        public void rejectNext(String code, String message) {
            next = new Rejected(code, message);
        }

        @Override
        public Created create(String companyId, String teamId, LocalDate periodStart, LocalDate periodEnd,
                              boolean releaseApproved, String requestId, String requestedBy) {
            calls.add(new Call(companyId, teamId, periodStart, periodEnd, releaseApproved, requestId, requestedBy));
            if (next != null) {
                Rejected e = next;
                next = null;
                throw e;
            }
            String snapshot = "{\"schema_version\":\"1\",\"purpose\":\"team\",\"request_id\":\"" + requestId
                    + "\",\"groups\":[]}";
            jdbc.update("INSERT INTO team_evaluations (request_id, snapshot_id, company_id, team_id, period_start, "
                            + "period_end, requested_by, release_approved, min_members, snapshot, snapshot_sha256, "
                            + "source_evaluation_ids) VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, 5, ?::jsonb, ?, '{}')",
                    requestId, UUID.randomUUID().toString(), companyId, teamId, Date.valueOf(periodStart),
                    Date.valueOf(periodEnd), requestedBy, releaseApproved, snapshot, "0".repeat(64));
            return new Created(requestId, releaseApproved, 5, true);
        }
    }
}
