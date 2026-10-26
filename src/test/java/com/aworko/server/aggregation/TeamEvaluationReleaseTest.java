package com.aworko.server.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aworko.server.aggregation.dto.TeamDashboard;
import com.aworko.server.aggregation.dto.TeamEvaluationRequest;
import com.aworko.server.aggregation.dto.TeamEvaluationSummary;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.messaging.OutboxPublisher;
import com.aworko.server.organization.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 공개 승인: 확정 평가가 있고 최소 인원을 넘는 업무 유형이 모두 같은 기간으로 집계 승인돼 있어야 true */
class TeamEvaluationReleaseTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final LocalDate FROM = LocalDate.parse("2026-10-19");
    static final LocalDate TO = LocalDate.parse("2026-10-25");
    static final AuthPrincipal LEAD = new AuthPrincipal("L-3001", "C-1", "T-1", UserRole.TEAM_LEAD);
    static final TeamDashboard.Approval APPROVED = new TeamDashboard.Approval(1L, "L-3001", OffsetDateTime.now(CLOCK));

    final TeamAggregationService aggregation = mock(TeamAggregationService.class);
    final TeamEvaluationGateway gateway = mock(TeamEvaluationGateway.class);
    final TeamEvaluationRows rows = mock(TeamEvaluationRows.class);
    final OutboxPublisher outbox = mock(OutboxPublisher.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);
    TeamEvaluationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        when(rows.find(eq("C-1"), eq("T-1"), anyString())).thenAnswer(inv -> Optional.of(new TeamEvaluationRows.Row(
                inv.getArgument(2), FROM, TO.plusDays(1), false, "대기", "L-3001", OffsetDateTime.now(CLOCK), null, null,
                false, null)));
        service = new TeamEvaluationService(aggregation, gateway, rows, outbox, tx,
                new TeamEvaluationProperties(Duration.ofMinutes(30)), CLOCK);
    }

    @Test
    void allReleasableTypesApprovedReleasesAndRestrictedTypesDoNotBlock() {
        dashboard(section("quote", 6, false, APPROVED), section("proposal", 2, true, null), section("inquiry_reply", 0, false, null));

        TeamEvaluationSummary summary = service.request(LEAD, "T-1", new TeamEvaluationRequest(FROM, TO));

        assertThat(released()).isTrue();
        assertThat(summary.unapprovedWorkTypes()).isEmpty();
        verify(outbox).teamEvaluate(summary.requestId(), "C-1");
    }

    @Test
    void anyUnapprovedReleasableTypeWithholdsAndIsReported() {
        dashboard(section("quote", 6, false, APPROVED), section("inquiry_reply", 7, false, null));

        TeamEvaluationSummary summary = service.request(LEAD, "T-1", new TeamEvaluationRequest(FROM, TO));

        assertThat(released()).isFalse();
        assertThat(summary.unapprovedWorkTypes()).containsExactly("inquiry_reply");
    }

    @Test
    void nothingToReleaseWithholds() {
        dashboard(section("quote", 3, true, null));

        service.request(LEAD, "T-1", new TeamEvaluationRequest(FROM, TO));

        assertThat(released()).isFalse();
    }

    private boolean released() {
        ArgumentCaptor<Boolean> release = ArgumentCaptor.forClass(Boolean.class);
        // MCP 기간 끝은 미포함이라 화면의 끝 날짜 + 1일
        verify(gateway).create(eq("C-1"), eq("T-1"), eq(FROM), eq(TO.plusDays(1)), release.capture(), anyString(),
                eq("L-3001"));
        return release.getValue();
    }

    private void dashboard(TeamDashboard.WorkTypeSection... sections) {
        when(aggregation.dashboard(LEAD, "T-1", FROM, TO)).thenReturn(new TeamDashboard("T-1", "1팀", FROM, TO, 5,
                new TeamDashboard.Stats(0, 0, 0, 0, 0, 0, 0, 0), List.of(), List.of(sections)));
        when(gateway.create(anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyString()))
                .thenAnswer(inv -> new TeamEvaluationGateway.Created(inv.getArgument(5), inv.getArgument(4), 5, true));
    }

    private static TeamDashboard.WorkTypeSection section(String type, int confirmed, boolean restricted,
                                                         TeamDashboard.Approval approval) {
        return new TeamDashboard.WorkTypeSection(type, type, confirmed, confirmed, confirmed, restricted, null,
                List.of(), List.of(), approval);
    }
}
