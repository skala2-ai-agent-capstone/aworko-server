package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.UserRole;
import com.aworko.server.support.FakeLlmChat;
import com.aworko.server.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** AI 업무 공간·업무 현황·운영 설정 API를 실제 Postgres(MCP 스키마) 위에서 확인한다. LLM은 대역(FakeLlmChat). */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeLlmChat.class})
@Sql("/db/seed_minimal.sql")
@Transactional
class WorkspaceIntegrationTest {

    static final String COMPANY = "C-LGCNS-SALES";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper objectMapper;
    @Autowired FakeLlmChat.Recorder llm;

    String member;      // E-1021, T-SALES-01
    String lead;        // L-3001, T-SALES-01 팀장
    String outsider;    // E-4001, 팀 없음

    @BeforeEach
    void setUp() {
        llm.calls.clear();
        member = token("E-1021", "T-SALES-01", UserRole.MEMBER);
        lead = token("L-3001", "T-SALES-01", UserRole.TEAM_LEAD);
        outsider = token("E-4001", null, UserRole.MEMBER);
    }

    @Test
    void workTypesAndCustomersForTheCreateForm() throws Exception {
        mvc.perform(auth(get("/api/work-types"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].work_type").value("rfp_analysis"))
                .andExpect(jsonPath("$.data[0].artifact_title").value("요구사항 대응표"))
                .andExpect(jsonPath("$.data[0].registration_fields[1].key").value("submission_deadline"));
        mvc.perform(auth(get("/api/customers"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].customer_id").value("CU-01"))
                .andExpect(jsonPath("$.data[0].name").value("가상 고객"));
    }

    @Test
    void createdWorkIsReadableByOwnerInFullAndByTeamLeadMasked() throws Exception {
        String workId = createWork("rfp_analysis", Map.of("customer_purpose", "담당 010-1234-5678로 회신",
                "submission_deadline", ""));

        mvc.perform(auth(get("/api/works/" + workId), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.stage").value("작성 중"))
                .andExpect(jsonPath("$.data.summary.status").value("in_progress"))
                .andExpect(jsonPath("$.data.editable").value(true))
                .andExpect(jsonPath("$.data.artifact_title").value("요구사항 대응표"))
                .andExpect(jsonPath("$.data.registration.customer_purpose").value("담당 010-1234-5678로 회신"))
                .andExpect(jsonPath("$.data.registration.submission_deadline").value(""))
                // 화면 이름과 함께 유형별 항목 순서로 펼친다
                .andExpect(jsonPath("$.data.registration_items.length()").value(2))
                .andExpect(jsonPath("$.data.registration_items[0].label").value("제출 기한"))
                .andExpect(jsonPath("$.data.registration_items[1].key").value("customer_purpose"))
                .andExpect(jsonPath("$.data.registration_items[1].label").value("고객 목적"));
        mvc.perform(auth(get("/api/works/" + workId), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editable").value(false))
                .andExpect(jsonPath("$.data.registration.customer_purpose").value("담당 [전화번호]로 회신"))
                .andExpect(jsonPath("$.data.registration_items[1].value").value("담당 [전화번호]로 회신"));
        mvc.perform(auth(get("/api/works/" + workId), outsider)).andExpect(status().isForbidden());

        // 시드와 같은 트랜잭션이라 생성 시각이 같아 순서는 보지 않는다
        mvc.perform(auth(get("/api/works"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].work_id", hasItem(workId)))
                .andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(auth(get("/api/works").param("scope", "team"), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].work_id", hasItem(workId)))
                .andExpect(jsonPath("$.data[*].employee_name", hasItem("가상 직원 A")));
        mvc.perform(auth(get("/api/works").param("scope", "team"), member)).andExpect(status().isForbidden());
    }

    @Test
    void createAsTheModalSendsItWithoutCustomerWithFrontKeyPurposeAndFiles() throws Exception {
        byte[] hwp = {1, 2, 3, 0, 4};
        String workId = objectMapper.readTree(mvc.perform(auth(multipart("/api/works"), member)
                        .file(new MockMultipartFile("files", "RFP_원문.txt", "text/plain",
                                "REQ-01 SSO 지원 담당 010-1111-2222".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "회의록.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx("회의: 국내 리전 필수")))
                        .file(new MockMultipartFile("files", "스캔.hwp", "application/x-hwp", hwp))
                        .param("title", "카카오엔터프라이즈향 RFP 분석").param("work_type", "rfp")
                        .param("purpose", "목적: SI 제안 사전 분석"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.summary.work_type").value("rfp_analysis"))
                .andExpect(jsonPath("$.data.summary.type_key").value("rfp"))
                .andExpect(jsonPath("$.data.summary.customer_name").doesNotExist())
                .andExpect(jsonPath("$.data.customer_id").doesNotExist())
                .andExpect(jsonPath("$.data.registration.purpose").value("목적: SI 제안 사전 분석"))
                .andExpect(jsonPath("$.data.registration_items[0].label").value("목적·요구사항"))
                .andExpect(jsonPath("$.data.source_documents.length()").value(3))
                .andExpect(jsonPath("$.data.source_documents[?(@.file_name == 'RFP_원문.txt')].text_extracted", hasItem(true)))
                .andExpect(jsonPath("$.data.source_documents[?(@.file_name == '스캔.hwp')].text_extracted", hasItem(false)))
                .andReturn().getResponse().getContentAsString()).path("data").path("summary").path("work_id").asText();
        assertThat(jdbc.queryForObject("SELECT content_masked FROM source_documents WHERE work_id = ? AND file_name = 'RFP_원문.txt'",
                String.class, workId)).isEqualTo("REQ-01 SSO 지원 담당 [전화번호]");

        // 꺼낸 텍스트(txt·docx)가 대화 문맥에 들어간다
        send(workId, member, "요구사항 정리").andExpect(status().isOk());
        assertThat(llm.last().get(0).get("content")).contains("REQ-01 SSO 지원", "회의: 국내 리전 필수",
                "목적·요구사항: 목적: SI 제안 사전 분석");

        String hwpId = jdbc.queryForObject("SELECT attachment_id FROM source_documents WHERE work_id = ? AND file_name = '스캔.hwp'",
                String.class, workId);
        mvc.perform(auth(get("/api/works/" + workId + "/source-documents/" + hwpId + "/file"), member))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/x-hwp"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(content().bytes(hwp));
        mvc.perform(auth(get("/api/works/" + workId + "/source-documents/" + hwpId + "/file"), lead))
                .andExpect(status().isForbidden());
        mvc.perform(auth(multipart("/api/works/" + workId + "/source-documents"), member)
                        .file(new MockMultipartFile("file", "정정공고.txt", "text/plain", "REQ-02 삭제".getBytes(StandardCharsets.UTF_8)))
                        .param("doc_type", "rfp_correction"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.doc_type").value("rfp_correction"))
                .andExpect(jsonPath("$.data.size_bytes").value("REQ-02 삭제".getBytes(StandardCharsets.UTF_8).length));

        create("{\"title\":\"고객 없는 회신\",\"work_type\":\"reply\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.summary.work_type").value("inquiry_reply"))
                .andExpect(jsonPath("$.data.summary.type_key").value("reply"));
        mvc.perform(auth(get("/api/works"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].work_id", hasItem(workId)));
    }

    @Test
    void createRejectsMissingOrUnknownTypeCustomerAndRegistrationKey() throws Exception {
        create("{\"title\":\"x\",\"work_type\":\"memo\",\"customer_id\":\"CU-01\"}").andExpect(status().isBadRequest());
        create("{\"title\":\"x\",\"work_type\":\"quote\",\"customer_id\":\"CU-NOPE\"}").andExpect(status().isNotFound());
        create("{\"title\":\"x\",\"work_type\":\"quote\",\"customer_id\":\"CU-01\",\"registration\":{\"Bad-Key\":\"v\"}}")
                .andExpect(status().isBadRequest());
        create("{\"work_type\":\"quote\",\"customer_id\":\"CU-01\"}").andExpect(status().isBadRequest());
        // 업무 유형은 필수다(자동 분류 없음). JSON·multipart 모두 막는다
        create("{\"title\":\"유형 미정\",\"customer_id\":\"CU-01\"}").andExpect(status().isBadRequest());
        create("{\"title\":\"유형 미정\",\"work_type\":\" \"}").andExpect(status().isBadRequest());
        mvc.perform(auth(multipart("/api/works"), member).param("title", "유형 미정"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("업무 유형(work_type)을 골라 주세요"));
        // 등록 기록이 없으면 registration은 생략된다
        create("{\"title\":\"등록 없음\",\"work_type\":\"quote\",\"customer_id\":\"CU-01\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.registration").doesNotExist());
    }

    @Test
    void sourceDocumentsAndChatGoThroughTheProxyWithContextAndHistory() throws Exception {
        String workId = createWork("rfp_analysis", Map.of("customer_purpose", "문서 검색 시간 단축"));
        String doc = "{\"file_name\":\"자료/RFP_원문.txt\",\"content\":\"REQ-01 SSO 지원\\n담당 010-1111-2222\"}";
        mvc.perform(auth(post("/api/works/" + workId + "/source-documents"), member)
                        .contentType(MediaType.APPLICATION_JSON).content(doc))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.doc_id").value("RFP_원문"))
                .andExpect(jsonPath("$.data.doc_type").value("rfp"))
                .andExpect(jsonPath("$.data.version").value("v1"))
                .andExpect(jsonPath("$.data.grade").value("대외비"));
        mvc.perform(auth(post("/api/works/" + workId + "/source-documents"), member)
                        .contentType(MediaType.APPLICATION_JSON).content(doc))
                .andExpect(jsonPath("$.data.version").value("v2"));
        assertThat(jdbc.queryForList("SELECT content_masked FROM source_documents WHERE work_id = ?", String.class, workId))
                .allMatch(c -> c.contains("[전화번호]") && !c.contains("010-1111"));
        mvc.perform(auth(post("/api/works/" + workId + "/source-documents"), lead)
                .contentType(MediaType.APPLICATION_JSON).content(doc)).andExpect(status().isForbidden());

        send(workId, member, "보안 요구사항 정리해줘").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reply").value("답변: 보안 요구사항 정리해줘"));
        List<Map<String, String>> first = llm.last();
        assertThat(first).hasSize(2);
        assertThat(first.get(0).get("role")).isEqualTo("system");
        assertThat(first.get(0).get("content")).contains("RFP 요구사항 분석", "고객 목적: 문서 검색 시간 단축", "REQ-01 SSO 지원");

        send(workId, member, "표로 만들어줘").andExpect(status().isOk());
        assertThat(llm.last()).extracting(m -> m.get("role")).containsExactly("system", "user", "assistant", "user");
        assertThat(llm.last().get(1).get("content")).isEqualTo("보안 요구사항 정리해줘");

        mvc.perform(auth(get("/api/works/" + workId + "/messages"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].seq").value(1))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[3].content").value("답변: 표로 만들어줘"));
        mvc.perform(auth(get("/api/works/" + workId + "/messages"), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4));
        send(workId, lead, "남의 업무에 질문").andExpect(status().isForbidden());
        send(workId, outsider, "남의 업무에 질문").andExpect(status().isForbidden());
    }

    @Test
    void savedArtifactVersionsAndCompletionMarkTheWorkAndQueueEvaluation() throws Exception {
        String workId = createWork("quote", Map.of("product", "Doc AI", "quantity", "120"));
        saveArtifact(workId, "견적 초안").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.artifact_id").value("ART-" + workId.substring(2)))
                .andExpect(jsonPath("$.data.version").value("v1"))
                .andExpect(jsonPath("$.data.author").value("employee"));
        saveArtifact(workId, "견적 최종 연락처 010-2222-3333").andExpect(jsonPath("$.data.version").value("v2"));
        assertThat(jdbc.queryForObject("SELECT content_masked FROM artifact_versions WHERE work_id = ? AND version = 'v2'",
                String.class, workId)).isEqualTo("견적 최종 연락처 [전화번호]");
        mvc.perform(auth(get("/api/works/" + workId), member))
                .andExpect(jsonPath("$.data.artifact.version").value("v2"))
                .andExpect(jsonPath("$.data.artifact.content").value("견적 최종 연락처 010-2222-3333"))
                .andExpect(jsonPath("$.data.summary.artifact_version").value("v2"));

        mvc.perform(auth(post("/api/work-events"), member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task_id\":\"" + workId + "\",\"deliverable_version\":\"v2\"}"))
                .andExpect(status().isAccepted());
        Map<String, Object> work = jdbc.queryForMap("SELECT status, completed_at FROM work_cases WHERE work_id = ?", workId);
        assertThat(work.get("status")).isEqualTo("completed");
        assertThat(work.get("completed_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT event_type, artifact_version, rubric_version FROM work_events WHERE work_id = ?",
                workId)).containsEntry("event_type", "work.completed").containsEntry("artifact_version", "v2")
                .containsEntry("rubric_version", "v2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type = 'EVALUATE'", Integer.class))
                .isEqualTo(1);
        mvc.perform(auth(get("/api/works/" + workId), member))
                .andExpect(jsonPath("$.data.summary.stage").value("평가 중"))
                .andExpect(jsonPath("$.data.summary.evaluation_status").value("대기"));
        saveArtifact(workId, "x").andExpect(status().isCreated());
        mvc.perform(auth(post("/api/works/" + workId + "/artifacts"), lead).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void summaryCountsStagesAndAveragesThisWeeksScores() throws Exception {
        jdbc.update("INSERT INTO evaluations (evaluation_id, work_id, company_id, artifact_id, artifact_version, "
                + "rubric_version, status, source_key) VALUES ('EVAL-S1', 'W-TEST-QT-01', ?, 'ART-TEST-QT-01', 'v2', 'v2', "
                + "'확정', 'EVAL-S1')", COMPANY);
        jdbc.update("INSERT INTO evaluation_drafts (evaluation_id, revision, items) VALUES ('EVAL-S1', 1, "
                + "'[{\"item_id\":\"q.1\",\"status\":\"채점\",\"score\":2},{\"item_id\":\"q.2\",\"status\":\"채점\",\"score\":1},"
                + "{\"item_id\":\"q.3\",\"status\":\"미관측\"}]'::jsonb)");

        mvc.perform(auth(get("/api/works/summary"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.stages['확정']").value(1))
                .andExpect(jsonPath("$.data.stages['작성 중']").value(1))
                .andExpect(jsonPath("$.data.needs_material").value(0))
                .andExpect(jsonPath("$.data.by_type[2].work_type").value("quote"))
                .andExpect(jsonPath("$.data.by_type[2].works").value(1))
                .andExpect(jsonPath("$.data.by_type[2].this_week").value(7.5))
                .andExpect(jsonPath("$.data.by_type[2].last_week").doesNotExist());
    }

    @Test
    void settingsListDocumentsAndRubricsForTeamLeadOnly() throws Exception {
        jdbc.update("INSERT INTO documents (doc_id, version, company_id, title, grade, collection, indexed, status, "
                + "effective_from, source_file) VALUES ('PROD-X', 'v1', ?, '제품 설명서', '사내', 'references', true, '현행', "
                + "'2026-01-01', 'prod.pdf')", COMPANY);
        jdbc.update("INSERT INTO rubric_items (doc_id, version, item_id, seq, item, criterion, score_2, score_0, "
                + "evidence_sources, related_clauses) VALUES ('RUB-quote', 'v2', 'q.1', 1, '문제 정의', '필수 기재?', '모두', "
                + "'3개 이상 누락', ARRAY['registration'], ARRAY[]::text[])");

        mvc.perform(auth(get("/api/settings/documents"), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].doc_id").value("PROD-X"))
                .andExpect(jsonPath("$.data[0].indexed").value(true));
        mvc.perform(auth(get("/api/settings/rubrics"), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].doc_id").value("RUB-quote"))
                .andExpect(jsonPath("$.data[0].work_type_label").value("견적서 작성"))
                .andExpect(jsonPath("$.data[0].item_count").value(1));
        mvc.perform(auth(get("/api/settings/rubrics/RUB-quote/v2"), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].item").value("문제 정의"))
                .andExpect(jsonPath("$.data.items[0].score2").value("모두"))
                .andExpect(jsonPath("$.data.items[0].score1").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].evidence_sources[0]").value("registration"));
        mvc.perform(auth(get("/api/settings/rubrics/RUB-none/v1"), lead)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/settings/documents"), member)).andExpect(status().isForbidden());
    }

    @Test
    void internalReferencesCanBeAttachedReadAndReachTheChat() throws Exception {
        jdbc.update("INSERT INTO documents (doc_id, version, company_id, title, grade, collection, indexed, status, "
                + "effective_from, content) VALUES "
                + "('PROD-X', 'v2', ?, '제품 설명서', '사내', 'references', true, '현행', '2026-02-01', 'SSO는 SAML 2.0만 지원'), "
                + "('PROD-X', 'v1', ?, '제품 설명서', '사내', 'references', true, '폐기', '2025-01-01', '예전 내용'), "
                + "('COST-X', 'v1', ?, '원가표', '기밀', NULL, false, '현행', NULL, '원가 비밀')",
                COMPANY, COMPANY, COMPANY);
        String workId = createWork("inquiry_reply", Map.of("question_original", "SSO 지원하나요?"));

        // 기밀(색인 안 함)은 빠지고, 폐기 버전은 상태와 함께 나온다
        mvc.perform(auth(get("/api/references"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].doc_id", hasItem("PROD-X")))
                .andExpect(jsonPath("$.data[?(@.doc_id == 'COST-X')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.doc_id == 'PROD-X' && @.version == 'v1')].status", hasItem("폐기")));

        attach(workId, member, "PROD-X", "v2").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source").value("attached"))
                .andExpect(jsonPath("$.data.title").value("제품 설명서"));
        attach(workId, member, "PROD-X", "v2").andExpect(status().isCreated());
        attach(workId, member, "COST-X", "v1").andExpect(status().isNotFound());
        attach(workId, lead, "PROD-X", "v1").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attached_references WHERE work_id = ?", Integer.class, workId))
                .isEqualTo(1);

        // 담당자 본인의 열람만 기록한다
        mvc.perform(auth(get("/api/works/" + workId + "/references/PROD-X/v2"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("SSO는 SAML 2.0만 지원"))
                .andExpect(jsonPath("$.data.status").value("현행"));
        mvc.perform(auth(get("/api/works/" + workId + "/references/PROD-X/v1"), lead)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/works/" + workId + "/references/COST-X/v1"), member)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForList("SELECT user_id || ':' || doc_id || ':' || version FROM reference_access_logs "
                + "WHERE work_id = ? ORDER BY seq", String.class, workId)).containsExactly("E-1021:PROD-X:v2");
        mvc.perform(auth(get("/api/works/" + workId), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internal_references.length()").value(1))
                .andExpect(jsonPath("$.data.internal_references[0].source").value("attached"));

        send(workId, member, "SSO 지원 여부 회신 써줘").andExpect(status().isOk());
        assertThat(llm.last().get(0).get("content"))
                .contains("[사내 참고 자료]", "PROD-X v2 제품 설명서 (현행)", "SSO는 SAML 2.0만 지원")
                .doesNotContain("원가 비밀", "예전 내용");

        mvc.perform(auth(delete("/api/works/" + workId + "/references/PROD-X/v2"), member)).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/works/" + workId + "/references/PROD-X/v2"), member))
                .andExpect(status().isNotFound());
        // 지정을 풀어도 열람 기록은 남아 accessed로 보인다
        mvc.perform(auth(get("/api/works/" + workId), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internal_references[0].source").value("accessed"));
    }

    private ResultActions attach(String workId, String token, String docId, String version) throws Exception {
        return mvc.perform(auth(post("/api/works/" + workId + "/references"), token).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("doc_id", docId, "version", version))));
    }

    private String createWork(String workType, Map<String, String> registration) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("title", "테스트 업무", "work_type", workType,
                "customer_id", "CU-01", "registration", registration));
        JsonNode created = objectMapper.readTree(create(body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return created.path("data").path("summary").path("work_id").asText();
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(auth(post("/api/works"), member).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions send(String workId, String token, String content) throws Exception {
        return mvc.perform(auth(post("/api/works/" + workId + "/messages"), token).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", content))));
    }

    private ResultActions saveArtifact(String workId, String content) throws Exception {
        return mvc.perform(auth(post("/api/works/" + workId + "/artifacts"), member).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", content))));
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private String token(String userId, String teamId, UserRole role) {
        return jwt.issue(new AuthPrincipal(userId, COMPANY, teamId, role)).accessToken();
    }

    private static <B extends MockHttpServletRequestBuilder> B auth(B b, String token) {
        b.header("Authorization", "Bearer " + token);
        return b;
    }
}
