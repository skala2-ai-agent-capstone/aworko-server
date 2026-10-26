package com.aworko.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.UserRole;
import com.aworko.server.support.FakeRubrics;
import com.aworko.server.support.TestcontainersConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** 운영 설정 평가 기준 쓰기 API: 팀장만, 초안만, 회사·작성자는 로그인 정보로 채워 MCP(대역)에 넘기는지 확인한다. */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeRubrics.class})
@Sql("/db/seed_minimal.sql")
@Transactional
class SettingsRubricIntegrationTest {

    static final String COMPANY = "C-LGCNS-SALES";
    static final String DRAFT_ITEM = "/api/settings/rubrics/RUB-quote/v3/items/quote.1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired FakeRubrics.Recorder mcp;

    String lead;      // L-3001 가상 팀장
    String member;    // E-1021

    @BeforeEach
    void setUp() {
        mcp.reset();
        lead = jwt.issue(new AuthPrincipal("L-3001", COMPANY, "T-SALES-01", UserRole.TEAM_LEAD)).accessToken();
        member = jwt.issue(new AuthPrincipal("E-1021", COMPANY, "T-SALES-01", UserRole.MEMBER)).accessToken();
        // seed_minimal의 현행 RUB-quote v2에 항목 하나, 그 복사본인 초안 v3
        jdbc.update("INSERT INTO documents (doc_id, version, company_id, title, grade, collection, indexed, status, "
                + "work_type, note) VALUES ('RUB-quote', 'v3', ?, '견적서 루브릭', '사내', 'rubrics', true, '초안', 'quote', "
                + "'관리자 수정 초안(가상 팀장)')", COMPANY);
        for (String version : List.of("v2", "v3")) {
            jdbc.update("INSERT INTO rubric_items (doc_id, version, item_id, seq, item, criterion, score_2, score_1, score_0, "
                    + "unobserved, evidence_sources, related_clauses) VALUES ('RUB-quote', ?, 'quote.1', 1, '문제 정의', "
                    + "'필수 기재?', '모두', '일부 누락', '3개 이상 누락', '등록 없음', ARRAY['업무 등록 내용'], ARRAY['ART-11'])",
                    version);
        }
    }

    @Test
    void itemSaveSendsTheDraftsTypeAndMcpFieldNamesAndReturnsTheSavedRow() throws Exception {
        send(patch(DRAFT_ITEM), lead, "{\"score2\":\"5개 요소 모두 기재\",\"related_clauses\":[\"ART-11\",\"ART-17\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.item_id").value("quote.1"))
                .andExpect(jsonPath("$.data.item.score2").value("5개 요소 모두 기재"))
                .andExpect(jsonPath("$.data.changed_fields[0]").value("score_2"));

        FakeRubrics.Call call = mcp.last();
        assertThat(call.path()).isEqualTo("/internal/rubrics/drafts/items");
        assertThat(call.args()).containsExactly(COMPANY, "quote", "문제 정의",
                Map.of("score_2", "5개 요소 모두 기재", "related_clauses", List.of("ART-11", "ART-17")), "가상 팀장");
        // 현행 v2는 그대로
        assertThat(jdbc.queryForObject("SELECT score_2 FROM rubric_items WHERE doc_id='RUB-quote' AND version='v2'",
                String.class)).isEqualTo("모두");
    }

    @Test
    void onlyDraftsOfMyCompanyCanBeEditedByTeamLeads() throws Exception {
        send(patch("/api/settings/rubrics/RUB-quote/v2/items/quote.1"), lead, "{\"score2\":\"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("초안만 고칠 수 있습니다. 수정 초안을 먼저 만드세요"));
        send(patch(DRAFT_ITEM), lead, "{}").andExpect(status().isBadRequest());
        send(patch("/api/settings/rubrics/RUB-quote/v3/items/quote.9"), lead, "{\"score2\":\"x\"}")
                .andExpect(status().isNotFound());
        String otherCompany = jwt.issue(new AuthPrincipal("L-9", "C-OTHER", null, UserRole.TEAM_LEAD)).accessToken();
        send(patch(DRAFT_ITEM), otherCompany, "{\"score2\":\"x\"}").andExpect(status().isNotFound());
        send(patch(DRAFT_ITEM), member, "{\"score2\":\"x\"}").andExpect(status().isForbidden());
        assertThat(mcp.calls).isEmpty();
    }

    @Test
    void mcpRejectionsKeepTheirMessageAndBecomeHttpStatuses() throws Exception {
        mcp.rejectNext("VALIDATION_ERROR", "#1 문제 정의: 현행 내규에 없는 조항 ['ART-99']");
        send(patch(DRAFT_ITEM), lead, "{\"related_clauses\":[\"ART-99\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("#1 문제 정의: 현행 내규에 없는 조항 ['ART-99']"));

        mcp.rejectNext("VERSION_CONFLICT", "평가 기준 초안이 이미 있습니다(quote)");
        send(post("/api/settings/rubrics/drafts"), lead, "{\"work_types\":[\"quote\"]}").andExpect(status().isConflict());

        mcp.rejectNext("NOT_FOUND", "평가 기준 초안 을(를) 찾을 수 없습니다");
        send(post("/api/settings/rubrics/approve"), lead, null).andExpect(status().isNotFound());

        mcp.rejectNext("FORBIDDEN", "X-Internal-Key가 필요합니다");
        send(post("/api/settings/rubrics/discard"), lead, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.message").value("평가 기준을 저장하지 못했습니다"));
    }

    @Test
    void draftApproveDiscardFillCompanyAndNameFromTheLogin() throws Exception {
        send(post("/api/settings/rubrics/drafts"), lead, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value("v3"))
                .andExpect(jsonPath("$.data[0].based_on_version").value("v2"));
        assertThat(mcp.last().args()).containsExactly(COMPANY, null, "가상 팀장");

        send(post("/api/settings/rubrics/approve"), lead, "{\"work_types\":[\"quote\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].retired_versions[0]").value("v2"));
        assertThat(mcp.last().args()).containsExactly(COMPANY, List.of("quote"), "가상 팀장");

        send(post("/api/settings/rubrics/discard"), lead, "{}").andExpect(status().isOk());
        assertThat(mcp.last()).isEqualTo(new FakeRubrics.Call("/internal/rubrics/discard", Arrays.asList(COMPANY, null)));

        send(post("/api/settings/rubrics/approve"), lead, "{\"work_types\":[\"rfp\"]}").andExpect(status().isBadRequest());
        send(post("/api/settings/rubrics/approve"), member, null).andExpect(status().isForbidden());
        assertThat(mcp.calls).hasSize(3);
    }

    @Test
    void rubricListShowsWhoMadeTheDraft() throws Exception {
        mvc.perform(auth(get("/api/settings/rubrics"), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value("v2"))
                .andExpect(jsonPath("$.data[1].version").value("v3"))
                .andExpect(jsonPath("$.data[1].status").value("초안"))
                .andExpect(jsonPath("$.data[1].note").value("관리자 수정 초안(가상 팀장)"));
    }

    @Test
    void policyClausesListEachCurrentClauseOnceWithItsFirstParagraph() throws Exception {
        jdbc.update("INSERT INTO documents (doc_id, version, company_id, title, grade, collection, indexed, status) "
                + "VALUES ('GL', 'v1.0', ?, '영업 AI 활용 지침', '사내', 'policies', true, '현행'), "
                + "('GL', 'v0.9', ?, '영업 AI 활용 지침', '사내', 'policies', true, '폐기')", COMPANY, COMPANY);
        jdbc.update("INSERT INTO document_chunks (doc_id, version, chunk_id, seq, clause_id, label, text) VALUES "
                + "('GL', 'v1.0', 'ART-11-1', 1, 'ART-11', '제11조 (업무 등록) 제1항', '① 업무 건을 생성할 때 기재한다.'), "
                + "('GL', 'v1.0', 'ART-11-2', 2, 'ART-11', '제11조 (업무 등록) 제2항', '② 둘째 단락'), "
                + "('GL', 'v1.0', 'ART-12', 3, 'ART-12', '제12조 (작업 요청)', '작업 요청 내용'), "
                + "('GL', 'v0.9', 'ART-99', 1, 'ART-99', '제99조 (옛 조항)', '폐기된 조항')");

        mvc.perform(auth(get("/api/settings/policy-clauses"), lead)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].clause_id").value("ART-11"))
                .andExpect(jsonPath("$.data[0].label").value("제11조 (업무 등록)"))
                .andExpect(jsonPath("$.data[0].text").value("① 업무 건을 생성할 때 기재한다."))
                .andExpect(jsonPath("$.data[1].clause_id").value("ART-12"));
        mvc.perform(auth(get("/api/settings/policy-clauses"), member)).andExpect(status().isForbidden());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(auth(request, token));
    }

    private static <B extends MockHttpServletRequestBuilder> B auth(B b, String token) {
        b.header("Authorization", "Bearer " + token);
        return b;
    }
}
