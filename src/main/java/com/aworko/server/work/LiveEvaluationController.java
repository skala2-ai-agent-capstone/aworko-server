package com.aworko.server.work;

import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.work.dto.LiveEvaluationRequest;
import com.aworko.server.work.dto.LiveEvaluationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

@Tag(name = "Live Evaluation", description = "실시간 프롬프팅 피드백 (버튼 요청 + 폴링)")
@RestController
@RequestMapping("/api/live-evaluations")
@RequiredArgsConstructor
public class LiveEvaluationController {

    private final LiveEvaluationService liveEvaluationService;

    @Operation(summary = "실시간 피드백 요청",
            description = "업무의 최근 완료 질문·응답 최대 5쌍으로 피드백을 요청한다(202). 사용자별 5분에 1회 — 초과 시 429와 "
                    + "retry_after_seconds. 완료된 질문·응답이 없으면 409, business-mcp에 닿지 못하면 503.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<LiveEvaluationResponse> request(@AuthenticationPrincipal AuthPrincipal me,
                                                       @Valid @RequestBody LiveEvaluationRequest request) {
        return ApiResponse.success(liveEvaluationService.request(me, request.workId()));
    }

    @Operation(summary = "실시간 피드백 조회(폴링)", description = "status가 완료·실패가 될 때까지 2~3초 간격으로 호출한다. 요청자 본인만.")
    @GetMapping("/{liveEvalId}")
    public ApiResponse<LiveEvaluationResponse> get(@AuthenticationPrincipal AuthPrincipal me,
                                                   @PathVariable String liveEvalId) {
        return ApiResponse.success(liveEvaluationService.get(me, liveEvalId));
    }
}
