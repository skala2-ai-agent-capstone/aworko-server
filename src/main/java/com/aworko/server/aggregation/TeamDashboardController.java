package com.aworko.server.aggregation;

import com.aworko.server.aggregation.dto.ApproveRequest;
import com.aworko.server.aggregation.dto.TeamDashboard;
import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Team Dashboard", description = "팀 집계·집계 승인 (팀장)")
@RestController
@RequestMapping("/api/teams/{teamId}/dashboard")
@RequiredArgsConstructor
public class TeamDashboardController {

    private final TeamAggregationService aggregationService;

    @Operation(summary = "팀 대시보드",
            description = "업무 유형별 집계. 기간을 생략하면 이번 주(월~일). 유형별 확정 대상 인원이 최소 인원 미만이면 "
                    + "그 유형은 restricted=true이고 상세 통계를 비운다. 담당 팀장만.")
    @GetMapping
    public ApiResponse<TeamDashboard> dashboard(
            @AuthenticationPrincipal AuthPrincipal me, @PathVariable String teamId,
            @Parameter(example = "2026-10-19") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(example = "2026-10-25") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(aggregationService.dashboard(me, teamId, from, to));
    }

    @Operation(summary = "집계 승인", description = "업무 유형·기간 단위로 집계를 승인하고 그 시점 결과를 저장한다. 최소 인원 미달이면 409.")
    @PostMapping("/approve")
    public ApiResponse<TeamDashboard.Approval> approve(@AuthenticationPrincipal AuthPrincipal me,
                                                       @PathVariable String teamId,
                                                       @Valid @RequestBody ApproveRequest request) {
        return ApiResponse.success(aggregationService.approve(me, teamId, request));
    }
}
