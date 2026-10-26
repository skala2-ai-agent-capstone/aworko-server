package com.aworko.server.support;

import com.aworko.server.settings.RubricGateway;
import com.aworko.server.settings.dto.RubricVersionChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * business-mcp /internal/rubrics/* 대역. 받은 요청을 기억하고 정해 둔 응답을 돌려준다.
 * 항목 저장은 글 칸만 rubric_items에 반영한다(백엔드가 저장 뒤 DB에서 다시 읽는지 확인용). 검사·색인은 MCP 쪽 책임이다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeRubrics {

    @Bean
    @Primary
    Recorder fakeRubricGateway(JdbcTemplate jdbc) {
        return new Recorder(jdbc);
    }

    /** 받은 요청 한 건. args는 MCP에 보낸 순서(company_id, …) */
    public record Call(String path, List<Object> args) {
    }

    public static class Recorder implements RubricGateway {

        private static final Set<String> TEXT_FIELDS = Set.of("criterion", "score_2", "score_1", "score_0",
                "unobserved", "needs_review");

        public final List<Call> calls = new CopyOnWriteArrayList<>();
        private final JdbcTemplate jdbc;
        private Rejected next;

        Recorder(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public void reset() {
            calls.clear();
            next = null;
        }

        /** 다음 요청 하나를 MCP 오류 응답으로 돌려준다 */
        public void rejectNext(String code, String message) {
            next = new Rejected(code, message);
        }

        public Call last() {
            return calls.get(calls.size() - 1);
        }

        @Override
        public List<RubricVersionChange> createDrafts(String companyId, List<String> workTypes, String createdBy) {
            record("/internal/rubrics/drafts", companyId, workTypes, createdBy);
            return List.of(new RubricVersionChange("quote", "RUB-quote", "v3", "v2", null));
        }

        @Override
        public List<String> updateItem(String companyId, String workType, String item, Map<String, Object> fields,
                                       String editedBy) {
            record("/internal/rubrics/drafts/items", companyId, workType, item, fields, editedBy);
            List<String> changed = new ArrayList<>();
            fields.forEach((field, value) -> {
                if (TEXT_FIELDS.contains(field)) {
                    jdbc.update("UPDATE rubric_items SET " + field + " = ? WHERE item = ? AND (doc_id, version) IN "
                            + "(SELECT doc_id, version FROM documents WHERE company_id = ? AND work_type = ? AND status = '초안')",
                            value, item, companyId, workType);
                }
                changed.add(field);
            });
            return changed;
        }

        @Override
        public List<RubricVersionChange> approve(String companyId, List<String> workTypes, String approvedBy) {
            record("/internal/rubrics/approve", companyId, workTypes, approvedBy);
            return List.of(new RubricVersionChange("quote", "RUB-quote", "v3", null, List.of("v2")));
        }

        @Override
        public List<RubricVersionChange> discard(String companyId, List<String> workTypes) {
            record("/internal/rubrics/discard", companyId, workTypes);
            return List.of(new RubricVersionChange("quote", "RUB-quote", "v3", null, null));
        }

        private void record(String path, Object... args) {
            calls.add(new Call(path, java.util.Arrays.asList(args)));
            if (next != null) {
                Rejected e = next;
                next = null;
                throw e;
            }
        }
    }
}
