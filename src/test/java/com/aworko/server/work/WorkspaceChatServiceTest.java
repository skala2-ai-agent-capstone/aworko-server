package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.organization.UserRole;
import com.aworko.server.work.dto.WorkSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkspaceChatServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "인사와 맺음말을 포함해 정확히 4문장으로 다시 작성해 주세요.",
            "인사와 맺음말을 포함해 정확히 3문장으로 다시 작성해 주세요.",
            "회신 초안을 5문장 이내로 작성해 주세요.",
            "문장 수 제한 없이 표로 정리해 주세요."
    })
    void sendsFormatRulesWithoutChangingConversationOrReply(String request) {
        WorkRows rows = mock(WorkRows.class);
        LlmChatGateway llm = mock(LlmChatGateway.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = spy(new WorkspaceChatService(rows, llm, jdbc, new ObjectMapper()));
        var me = new AuthPrincipal("U-TEST", "C-TEST", "T-TEST", UserRole.MEMBER);
        var summary = new WorkSummary("W-TEST", "문서 검색 문의", "inquiry_reply", "고객 질의 답변 작성",
                "reply", null, me.userId(), "테스트 직원", "in_progress", "작성 중", null, null, null, null);
        when(rows.owned(me, "W-TEST")).thenReturn(new WorkRows.Row(summary, "T-TEST", null, null, null));
        when(jdbc.queryForObject("SELECT registration::text FROM work_cases WHERE work_id = ?",
                String.class, "W-TEST")).thenReturn("{\"purpose\":\"회신 예정일 미정\"}");
        var history = List.of(Map.of("role", "user", "content", "5문장 이내로 써 주세요."),
                Map.of("role", "assistant", "content", "이전 답변입니다."));
        doReturn(history).when(service).history("W-TEST");
        when(llm.reply(eq("W-TEST"), eq(me.userId()), anyList())).thenReturn("모델 응답 원문");

        var result = service.send(me, "W-TEST", request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm).reply(eq("W-TEST"), eq(me.userId()), messages.capture());
        verifyNoMoreInteractions(llm);
        var sent = messages.getValue();
        assertThat(sent).extracting(m -> m.get("role")).containsExactly("system", "user", "assistant", "user");
        assertThat(sent.subList(1, 3)).isEqualTo(history);
        assertThat(sent.get(3)).isEqualTo(Map.of("role", "user", "content", request));
        assertThat(sent.get(0).get("content")).contains(
                "가장 최근 사용자 요청", "정확히 N문장", "N문장 이내", "인사와 맺음말도 각각 문장 수에 포함",
                "N-2문장", "제목·머리말·후기", "문장 수 제한이 없으면", "[회신 예정일: 내부 확인 후 입력]",
                "사내 기밀", "문서 검색 문의", "회신 예정일 미정");
        assertThat(result.question()).isEqualTo(request);
        assertThat(result.reply()).isEqualTo("모델 응답 원문");
    }
}
