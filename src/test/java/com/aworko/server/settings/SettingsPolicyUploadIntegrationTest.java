package com.aworko.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.UserRole;
import com.aworko.server.support.FakePolicyDocuments;
import com.aworko.server.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내규 올리기 → 평가 기준 초안 생성 요청: 팀장만, MCP(대역)에 적재한 뒤 rubric_generations 행과 RUBRIC_GENERATE 아웃박스를
 * 함께 쓰는지, 진행 중·오래된 대기·같은 내용 재업로드·MCP 검사 실패를 어떻게 다루는지 확인한다.
 */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakePolicyDocuments.class})
@Sql("/db/seed_minimal.sql")
@Transactional
class SettingsPolicyUploadIntegrationTest {

    static final String COMPANY = "C-LGCNS-SALES";
    static final List<String> ALL_TYPES = List.of("rfp_analysis", "proposal", "quote", "inquiry_reply");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper objectMapper;
    @Autowired FakePolicyDocuments.Recorder mcp;
    @Autowired RubricGenerationService generations;

    String lead;      // L-3001 가상 팀장
    String member;    // E-1021

    @BeforeEach
    void setUp() {
        mcp.reset();
        lead = jwt.issue(new AuthPrincipal("L-3001", COMPANY, "T-SALES-01", UserRole.TEAM_LEAD)).accessToken();
        member = jwt.issue(new AuthPrincipal("E-1021", COMPANY, "T-SALES-01", UserRole.MEMBER)).accessToken();
    }

    @Test
    void uploadStoresThePolicyThroughMcpAndQueuesDraftGenerationForAllWorkTypes() throws Exception {
        MockMultipartHttpServletRequestBuilder request = uploadRequest(lead, "영업업무내규_v1.2.pdf", "v1.2");
        request.param("title", "영업 업무 내규");
        request.param("effective_from", "2026-11-01");
        String generationId = json(mvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.document.doc_id").value("POL-SALES"))
                .andExpect(jsonPath("$.data.document.version").value("v1.2"))
                .andExpect(jsonPath("$.data.document.diff.changed[0]").value("ART-04"))
                .andExpect(jsonPath("$.data.document.rag_reloaded").value(true))
                .andExpect(jsonPath("$.data.generation.status").value("대기"))
                .andExpect(jsonPath("$.data.generation.policy_version").value("v1.2"))
                .andExpect(jsonPath("$.data.generation.source_file").value("영업업무내규_v1.2.pdf"))
                .andReturn().getResponse().getContentAsString())
                .path("data").path("generation").path("generation_id").asText();

        var sent = mcp.uploads.get(0);
        assertThat(sent.companyId()).isEqualTo(COMPANY);
        assertThat(sent.version()).isEqualTo("v1.2");
        assertThat(sent.sourceFile()).isEqualTo("영업업무내규_v1.2.pdf");
        assertThat(new String(sent.content(), StandardCharsets.UTF_8)).isEqualTo("제1조 목적");
        assertThat(sent.title()).isEqualTo("영업 업무 내규");
        assertThat(sent.effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(sent.uploadedBy()).isEqualTo("가상 팀장");

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT type, routing_key, payload::text AS payload FROM outbox_events WHERE aggregate_id = ?", generationId);
        assertThat(outbox.get("type")).isEqualTo("RUBRIC_GENERATE");
        assertThat(outbox.get("routing_key")).isEqualTo("rubric_generate");
        JsonNode payload = objectMapper.readTree((String) outbox.get("payload"));
        assertThat(payload.path("type").asText()).isEqualTo("RUBRIC_GENERATE");
        assertThat(payload.path("generation_id").asText()).isEqualTo(generationId);
        assertThat(payload.path("company_id").asText()).isEqualTo(COMPANY);
        assertThat(payload.path("policy_doc_id").asText()).isEqualTo("POL-SALES");
        assertThat(payload.path("policy_version").asText()).isEqualTo("v1.2");
        assertThat(objectMapper.convertValue(payload.path("work_types"), List.class)).isEqualTo(ALL_TYPES);
        assertThat(payload.path("requested_by").asText()).isEqualTo("L-3001");
        assertThat(payload.path("attempt").asInt()).isZero();
        assertThat(payload.path("message_id").asText()).isNotBlank();
    }

    @Test
    void onlyTeamLeadsMayUploadAndOnlyPdfOrTxtIsSentToMcp() throws Exception {
        upload(member, "내규.pdf", "v1.2").andExpect(status().isForbidden());
        upload(lead, "내규.docx", "v1.2").andExpect(status().isBadRequest());
        upload(lead, "내규.txt", " ").andExpect(status().isBadRequest());

        assertThat(mcp.uploads).isEmpty();
        assertThat(count("rubric_generations")).isZero();
    }

    @Test
    void mcpValidationFailureIsShownAsIsAndNothingIsQueued() throws Exception {
        mcp.rejectNext("VALIDATION_ERROR", "평가 기준 생성·현행 평가 기준에 필요한 조항이 새 내규에 없습니다: ART-14");

        upload(lead, "내규.pdf", "v1.2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("평가 기준 생성·현행 평가 기준에 필요한 조항이 새 내규에 없습니다: ART-14"));

        mcp.rejectNext("VERSION_CONFLICT", "POL-SALES v1.1은(는) 이미 있습니다. 새 버전 번호로 올리세요");
        upload(lead, "내규.pdf", "v1.1").andExpect(status().isConflict());

        assertThat(count("rubric_generations")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void reuploadingTheSameContentDoesNotStartAnotherGeneration() throws Exception {
        mcp.duplicateNext();

        upload(lead, "내규.pdf", "v1.2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.document.duplicate").value(true))
                .andExpect(jsonPath("$.data.generation").doesNotExist());

        assertThat(count("rubric_generations")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void aRunningGenerationBlocksUploadsUntilItFinishesOrGoesStale() throws Exception {
        upload(lead, "내규.pdf", "v1.2").andExpect(status().isCreated());

        upload(lead, "내규.pdf", "v1.3").andExpect(status().isConflict());
        mvc.perform(post("/api/settings/rubric-generations").header("Authorization", "Bearer " + lead))
                .andExpect(status().isConflict());
        assertThat(mcp.uploads).hasSize(1);   // 내규를 바꾸기 전에 막는다

        // Worker가 없어 오래 대기 중이면 시간 초과 실패로 보이고, 다음 업로드가 그 건을 실패로 기록한 뒤 진행한다
        jdbc.update("UPDATE rubric_generations SET created_at = now() - interval '2 hours'");
        mvc.perform(get("/api/settings/rubric-generations/latest").header("Authorization", "Bearer " + lead))
                .andExpect(jsonPath("$.data.status").value("실패"))
                .andExpect(jsonPath("$.data.error").value("TIMED_OUT"))
                .andExpect(jsonPath("$.data.error_message").isNotEmpty());

        upload(lead, "내규.pdf", "v1.3").andExpect(status().isCreated());
        assertThat(jdbc.queryForList("SELECT status || ':' || coalesce(error, '') FROM rubric_generations "
                + "ORDER BY created_at", String.class)).containsExactly("실패:TIMED_OUT", "대기:");
    }

    @Test
    void regenerateUsesTheCurrentPolicyVersion() throws Exception {
        mvc.perform(post("/api/settings/rubric-generations").header("Authorization", "Bearer " + lead))
                .andExpect(status().isNotFound());

        jdbc.update("INSERT INTO documents (doc_id, version, company_id, title, grade, collection, indexed, status, "
                + "effective_from, source_file) VALUES ('POL-SALES', 'v1.1', ?, '영업 업무 내규', '사내', 'policies', true, "
                + "'현행', '2026-06-01', '영업업무내규_v1.1.pdf')", COMPANY);

        mvc.perform(post("/api/settings/rubric-generations").header("Authorization", "Bearer " + lead))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("대기"))
                .andExpect(jsonPath("$.data.policy_doc_id").value("POL-SALES"))
                .andExpect(jsonPath("$.data.policy_version").value("v1.1"))
                .andExpect(jsonPath("$.data.source_file").value("영업업무내규_v1.1.pdf"));
        assertThat(mcp.uploads).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type = 'RUBRIC_GENERATE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void resultsFinishTheGenerationOnceAndLateMessagesAreIgnored() throws Exception {
        String id = json(upload(lead, "내규.pdf", "v1.2").andReturn().getResponse().getContentAsString())
                .path("data").path("generation").path("generation_id").asText();
        mvc.perform(get("/api/settings/rubric-generations/latest").header("Authorization", "Bearer " + member))
                .andExpect(status().isForbidden());

        generations.complete(json("{\"type\":\"RUBRIC_GENERATED\",\"generation_id\":\"" + id + "\",\"company_id\":\""
                + COMPANY + "\",\"drafts\":[{\"work_type\":\"quote\",\"doc_id\":\"RUB-quote\",\"version\":\"v3\","
                + "\"replaced_draft\":false}]}"));
        // 늦게 온 DLQ·다른 회사 이름의 결과는 바꾸지 않는다
        generations.fail(json("{\"generation_id\":\"" + id + "\"}"), "RUBRIC_INVALID");
        generations.complete(json("{\"generation_id\":\"" + id + "\",\"company_id\":\"C-OTHER\",\"drafts\":[]}"));

        mvc.perform(get("/api/settings/rubric-generations/latest").header("Authorization", "Bearer " + lead))
                .andExpect(jsonPath("$.data.generation_id").value(id))
                .andExpect(jsonPath("$.data.status").value("완료"))
                .andExpect(jsonPath("$.data.drafts[0].work_type").value("quote"))
                .andExpect(jsonPath("$.data.drafts[0].doc_id").value("RUB-quote"))
                .andExpect(jsonPath("$.data.drafts[0].version").value("v3"))
                .andExpect(jsonPath("$.data.error").doesNotExist())
                .andExpect(jsonPath("$.data.finished_at").isNotEmpty());
    }

    @Test
    void deadLetterMarksTheGenerationFailedWithTheWorkerCode() throws Exception {
        String id = json(upload(lead, "내규.pdf", "v1.2").andReturn().getResponse().getContentAsString())
                .path("data").path("generation").path("generation_id").asText();

        generations.fail(json("{\"generation_id\":\"" + id + "\",\"attempt\":3}"), "POLICY_INCOMPLETE");

        mvc.perform(get("/api/settings/rubric-generations/latest").header("Authorization", "Bearer " + lead))
                .andExpect(jsonPath("$.data.status").value("실패"))
                .andExpect(jsonPath("$.data.error").value("POLICY_INCOMPLETE"))
                .andExpect(jsonPath("$.data.error_message").value("내규에서 평가 기준에 필요한 조항을 찾지 못했습니다"));
        // 실패 뒤에는 다시 생성할 수 있다
        jdbc.update("INSERT INTO documents (doc_id, version, company_id, title, grade, collection, indexed, status) "
                + "VALUES ('POL-SALES', 'v1.2', ?, '영업 업무 내규', '사내', 'policies', true, '현행')", COMPANY);
        mvc.perform(post("/api/settings/rubric-generations").header("Authorization", "Bearer " + lead))
                .andExpect(status().isAccepted());
    }

    private ResultActions upload(String token, String fileName, String version) throws Exception {
        return mvc.perform(uploadRequest(token, fileName, version));
    }

    @Test
    void swaggerListsTheUploadFormFieldsInWireNames() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/settings/documents'].post.requestBody.content['multipart/form-data']")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.PolicyUploadForm.properties.effective_from").exists())
                .andExpect(jsonPath("$.components.schemas.PolicyDocumentSaved.properties.diff['$ref']")
                        .value("#/components/schemas/PolicyDocumentDiff"))
                .andExpect(jsonPath("$.components.schemas.RubricGeneration.properties.drafts.items['$ref']")
                        .value("#/components/schemas/RubricGenerationDraft"));
    }

    private static MockMultipartHttpServletRequestBuilder uploadRequest(String token, String fileName, String version) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/settings/documents")
                .file(new MockMultipartFile("file", fileName, "application/pdf",
                        "제1조 목적".getBytes(StandardCharsets.UTF_8)));
        request.param("version", version);
        request.header("Authorization", "Bearer " + token);
        return request;
    }

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
