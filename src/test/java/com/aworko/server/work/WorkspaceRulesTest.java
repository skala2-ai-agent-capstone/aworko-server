package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.work.dto.CreateWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkspaceRulesTest {

    @Test
    void stageFollowsLatestEvaluationThenWorkStatus() {
        assertThat(WorkRows.stage("in_progress", null)).isEqualTo("작성 중");
        assertThat(WorkRows.stage("completed", null)).isEqualTo("평가 대기");
        assertThat(WorkRows.stage("completed", "근거 확인 중")).isEqualTo("평가 중");
        assertThat(WorkRows.stage("completed", "검토 대기")).isEqualTo("검토 중");
        assertThat(WorkRows.stage("completed", "자료 보류")).isEqualTo("자료 보류");
        assertThat(WorkRows.stage("completed", "처리 실패")).isEqualTo("평가 실패");
        assertThat(WorkRows.STAGES).contains("작성 중", "평가 대기", "평가 중", "확인 대기", "검토 중", "확정", "자료 보류", "평가 실패");
    }

    @Test
    void docIdIsFileNameWithoutPathAndExtension() {
        assertThat(WorkspaceService.docIdOf("C:\\자료\\RFP_원문 v2.txt")).isEqualTo("RFP_원문 v2");
        assertThat(WorkspaceService.docIdOf("회의록.2026.10.md")).isEqualTo("회의록.2026.10");
        assertThat(WorkspaceService.docIdOf(".txt")).isEqualTo(".txt");
        assertThat(WorkspaceService.docIdOf("  ")).isEqualTo("DOC");
    }

    @Test
    void nextVersionUsesHighestNumberNotStringOrder() {
        assertThat(WorkspaceService.maxVersion(List.of("v1", "v10", "v9", "최종"))).isEqualTo(10);
        assertThat(WorkspaceService.maxVersion(List.of())).isZero();
    }

    @Test
    void scoreIsScoredItemsOutOfTenSkippingUnobserved() throws Exception {
        var items = new ObjectMapper().readTree("[{\"status\":\"채점\",\"score\":2},{\"status\":\"채점\",\"score\":1},"
                + "{\"status\":\"미관측\"},{\"status\":\"검토 필요\",\"score\":0}]");
        assertThat(WorkspaceService.score10(items)).isEqualTo(7.5);
        assertThat(WorkspaceService.score10(new ObjectMapper().readTree("[{\"status\":\"미관측\"}]"))).isNull();
        assertThat(WorkspaceService.score10(null)).isNull();
    }

    @Test
    void frontKeysAndPurposeAreAccepted() {
        assertThat(WorkspaceService.parseWorkType("rfp")).isEqualTo(WorkType.RFP_ANALYSIS);
        assertThat(WorkspaceService.parseWorkType("reply")).isEqualTo(WorkType.INQUIRY_REPLY);
        assertThat(WorkTypeCatalog.typeKey(WorkType.INQUIRY_REPLY)).isEqualTo("reply");
        assertThat(WorkspaceService.registrationOf(new CreateWorkRequest("t", null, null, " 목적 ", null)))
                .containsExactly(Map.entry("purpose", "목적"));
        assertThat(WorkspaceService.registrationOf(new CreateWorkRequest("t", null, null, null, null))).isNull();
        assertThat(WorkspaceService.fileNameOf("C:\\자료\\RFP.pdf")).isEqualTo("RFP.pdf");
    }

    @Test
    void unknownWorkTypeIsValidationError() {
        assertThat(WorkspaceService.parseWorkType(" ")).isNull();
        assertThat(WorkspaceService.parseWorkType("quote")).isEqualTo(WorkType.QUOTE);
        assertThatThrownBy(() -> WorkspaceService.parseWorkType("memo")).isInstanceOf(BusinessException.class);
    }
}
