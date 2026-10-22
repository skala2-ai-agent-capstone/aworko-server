package com.aworko.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.aworko.server.aggregation.AggregationApproval;
import com.aworko.server.aggregation.AggregationApprovalRepository;
import com.aworko.server.document.Document;
import com.aworko.server.document.DocumentRepository;
import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.humanaction.HumanAction;
import com.aworko.server.humanaction.HumanActionRepository;
import com.aworko.server.humanaction.HumanActionType;
import com.aworko.server.humanaction.PolicyRule;
import com.aworko.server.humanaction.PolicyRuleRepository;
import com.aworko.server.messaging.EvaluationFailure;
import com.aworko.server.messaging.EvaluationFailureRepository;
import com.aworko.server.messaging.OutboxEvent;
import com.aworko.server.messaging.OutboxEventRepository;
import com.aworko.server.organization.User;
import com.aworko.server.organization.UserRepository;
import com.aworko.server.organization.UserRole;
import com.aworko.server.support.TestcontainersConfiguration;
import com.aworko.server.work.ArtifactVersionRepository;
import com.aworko.server.work.LiveEvaluation;
import com.aworko.server.work.LiveEvaluationRepository;
import com.aworko.server.work.LiveEvaluationStatus;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import com.aworko.server.work.WorkCase;
import com.aworko.server.work.WorkCaseRepository;
import com.aworko.server.work.WorkType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 스키마(001_schema.sql) + Flyway V1 위에서 엔티티 매핑이 실제로 읽고 쓰이는지 확인한다.
 * 컨텍스트가 뜬다는 것 자체가 ddl-auto=validate 통과를 뜻한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Sql("/db/seed_minimal.sql")
@Transactional
class PersistenceMappingTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired WorkCaseRepository workCases;
    @Autowired ArtifactVersionRepository artifacts;
    @Autowired DocumentRepository documents;
    @Autowired EvaluationRepository evaluations;
    @Autowired HumanActionRepository humanActions;
    @Autowired PolicyRuleRepository policyRules;
    @Autowired OutboxEventRepository outbox;
    @Autowired EvaluationFailureRepository failures;
    @Autowired AggregationApprovalRepository approvals;
    @Autowired LiveEvaluationRepository liveEvaluations;
    @Autowired EntityManager entityManager;

    @Test
    void flywayCreatesOnlyBackendTablesOnTopOfExistingSchema() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_backend_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(applied).contains("0", "1");
        Integer backendTables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name IN "
                        + "('outbox_events','evaluation_failures','aggregation_approvals')", Integer.class);
        assertThat(backendTables).isEqualTo(3);
    }

    @Test
    void readsCodedEnumsFromExistingTables() {
        User lead = users.findByUserIdAndCompanyId("L-3001", "C-LGCNS-SALES").orElseThrow();
        assertThat(lead.getRole()).isEqualTo(UserRole.TEAM_LEAD);

        WorkCase work = workCases.findByWorkIdAndCompanyId("W-TEST-QT-01", "C-LGCNS-SALES").orElseThrow();
        assertThat(work.getWorkType()).isEqualTo(WorkType.QUOTE);
        assertThat(artifacts.findByWorkIdAndVersion("W-TEST-QT-01", "v2")).hasSize(1);

        List<Document> rubrics = documents.findByCompanyIdAndCollectionAndWorkTypeAndStatusOrderByEffectiveFromDesc(
                "C-LGCNS-SALES", Document.COLLECTION_RUBRICS, WorkType.QUOTE, Document.STATUS_CURRENT);
        assertThat(rubrics).extracting(Document::getVersion).containsExactly("v2");

        assertThat(policyRules.findByKindOrderBySeq(PolicyRule.KIND_MASKING))
                .extracting(PolicyRule::getReplaceWith).containsExactly("[전화번호]");
    }

    @Test
    void workCaseWithoutWorkTypeIsReadable() {
        WorkCase work = workCases.findByWorkIdAndCompanyId("W-TEST-NEW-01", "C-LGCNS-SALES").orElseThrow();
        assertThat(work.getWorkType()).isNull();
        assertThat(work.getTitle()).isEqualTo("유형 미정 업무");
    }

    @Test
    void writesLiveEvaluationRequestWithKoreanStatus() {
        OffsetDateTime now = OffsetDateTime.now();
        LiveEvaluation saved = liveEvaluations.saveAndFlush(
                LiveEvaluation.request("W-TEST-QT-01", "E-1021", 3, 7, "RUB-quote", "v2", now));

        assertThat(jdbc.queryForObject("SELECT status FROM live_evaluations WHERE live_eval_id = ?", String.class,
                saved.getLiveEvalId())).isEqualTo("대기");

        jdbc.update("UPDATE live_evaluations SET status = '완료', items = '[{\"item_id\":\"q.1\"}]'::jsonb "
                + "WHERE live_eval_id = ?", saved.getLiveEvalId());
        entityManager.clear();
        LiveEvaluation done = liveEvaluations.findById(saved.getLiveEvalId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(LiveEvaluationStatus.COMPLETED);
        assertThat(done.getItems().get(0).get("item_id").asText()).isEqualTo("q.1");
        assertThat(liveEvaluations.findFirstByRequestedByOrderByRequestedAtDesc("E-1021"))
                .map(LiveEvaluation::getLiveEvalId).contains(saved.getLiveEvalId());
    }

    @Test
    void writesEvaluationWithKoreanStatusCodeAndIdRule() {
        String id = Evaluation.idFor("W-TEST-QT-01", "v2");
        assertThat(id).isEqualTo("EVAL-TEST-QT-01-v2");

        evaluations.saveAndFlush(Evaluation.create(id, "W-TEST-QT-01", "C-LGCNS-SALES", "ART-TEST-QT-01", "v2", "v2",
                Evaluation.defaultSourceKey("W-TEST-QT-01", "v2", "v2")));

        assertThat(jdbc.queryForObject("SELECT status FROM evaluations WHERE evaluation_id = ?", String.class, id))
                .isEqualTo("대기");
        assertThat(evaluations.findBySourceKey("W-TEST-QT-01:v2:v2").orElseThrow().getStatus())
                .isEqualTo(EvaluationStatus.WAITING);
    }

    @Test
    void writesJsonbColumnsOfHumanActionAndBackendTables() {
        String id = Evaluation.idFor("W-TEST-QT-01", "v2");
        evaluations.saveAndFlush(Evaluation.create(id, "W-TEST-QT-01", "C-LGCNS-SALES", "ART-TEST-QT-01", "v2", "v2",
                Evaluation.defaultSourceKey("W-TEST-QT-01", "v2", "v2")));
        var payload = objectMapper.createObjectNode().put("evaluation_id", id).put("comment", "근거 재확인 요청");

        humanActions.saveAndFlush(HumanAction.record("W-TEST-QT-01", "E-1021", HumanActionType.APPEAL, "v2",
                payload, payload));
        outbox.saveAndFlush(OutboxEvent.pending(id, "EVALUATE", "evaluate", payload));
        failures.saveAndFlush(EvaluationFailure.of(id, payload, "attempt 한도 초과"));
        approvals.saveAndFlush(AggregationApproval.of("C-LGCNS-SALES", "T-SALES-01", WorkType.QUOTE,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "L-3001", payload));

        assertThat(jdbc.queryForObject(
                "SELECT payload->>'comment' FROM human_actions WHERE work_id = 'W-TEST-QT-01' AND action = 'appeal'",
                String.class)).isEqualTo("근거 재확인 요청");
        assertThat(outbox.findByStatusOrderById(OutboxEvent.PENDING, Limit.of(10))).hasSize(1);
        assertThat(failures.findByEvaluationIdAndReprocessedAtIsNull(id)).hasSize(1);
        assertThat(approvals.findByTeamIdAndWorkTypeOrderByApprovedAtDesc("T-SALES-01", WorkType.QUOTE)).hasSize(1);
    }
}
