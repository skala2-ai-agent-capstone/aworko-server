package com.aworko.server.aggregation;

import com.aworko.server.aggregation.dto.TeamEvaluationDetail;
import com.aworko.server.aggregation.dto.TeamEvaluationRequest;
import com.aworko.server.aggregation.dto.TeamEvaluationSummary;
import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Team Evaluation", description = "팀 코칭 리포트 (팀장)")
@RestController
@RequestMapping("/api/teams/{teamId}/evaluations")
@RequiredArgsConstructor
public class TeamEvaluationController {

    private final TeamEvaluationService teamEvaluationService;

    @Operation(summary = "팀 평가 요청",
            description = "기간(끝 날짜 포함, 최대 93일)의 확정 평가 집계로 팀 코칭 리포트를 만든다(202, 대기). 담당 팀장만. "
                    + "기간에 확정 평가가 있는 업무 유형을 모두 같은 기간으로 집계 승인해 두어야 수치가 공개된다(release_approved). "
                    + "아니면 보류(withheld) 리포트가 되고 unapproved_work_types에 빠진 유형이 온다. "
                    + "상태는 목록·상세를 폴링한다.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TeamEvaluationSummary> request(@AuthenticationPrincipal AuthPrincipal me,
                                                      @PathVariable String teamId,
                                                      @Valid @RequestBody TeamEvaluationRequest request) {
        return ApiResponse.success(teamEvaluationService.request(me, teamId, request));
    }

    @Operation(summary = "팀 평가 목록", description = "최근 요청 20건(최신순). 담당 팀장만.")
    @GetMapping
    public ApiResponse<List<TeamEvaluationSummary>> list(@AuthenticationPrincipal AuthPrincipal me,
                                                         @PathVariable String teamId) {
        return ApiResponse.success(teamEvaluationService.list(me, teamId));
    }

    @Operation(summary = "팀 평가 상세",
            description = "상태와 팀 리포트(완료일 때). 리포트에는 팀 집계 수치와 근거·코칭 선택만 있고 개인 정보는 없다. 담당 팀장만.")
    @GetMapping("/{requestId}")
    public ApiResponse<TeamEvaluationDetail> detail(@AuthenticationPrincipal AuthPrincipal me,
                                                    @PathVariable String teamId, @PathVariable String requestId) {
        return ApiResponse.success(teamEvaluationService.detail(me, teamId, requestId));
    }
}
