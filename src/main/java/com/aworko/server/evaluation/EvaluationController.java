package com.aworko.server.evaluation;

import com.aworko.server.evaluation.dto.AppealRequest;
import com.aworko.server.evaluation.dto.EvaluationDetail;
import com.aworko.server.evaluation.dto.EvaluationSummary;
import com.aworko.server.evaluation.dto.StatusResponse;
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

@Tag(name = "Evaluation", description = "개인 평가 리포트·본인 확인·이의 제기")
@RestController
@RequestMapping("/api/evaluations")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    @Operation(summary = "내 평가 목록", description = "내 업무의 평가를 최신순으로 돌려준다.")
    @GetMapping
    public ApiResponse<List<EvaluationSummary>> listMine(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(evaluationService.listMine(me));
    }

    @Operation(summary = "개인 평가 리포트",
            description = "항목별 점수·근거·코칭과 진행 상태. 평가가 진행 중이면 report.items가 비어 있으니 "
                    + "이 API를 2~3초 간격으로 다시 조회해 진행 상태(display_status)를 확인한다. 본인 또는 배정된 팀장만.")
    @GetMapping("/{evaluationId}")
    public ApiResponse<EvaluationDetail> detail(@AuthenticationPrincipal AuthPrincipal me,
                                                @PathVariable String evaluationId) {
        return ApiResponse.success(evaluationService.detail(me, evaluationId));
    }

    @Operation(summary = "본인 확인(확정)", description = "'확인 대기'일 때만 가능하다. 그 외 상태면 409.")
    @PostMapping("/{evaluationId}/confirm")
    public ApiResponse<StatusResponse> confirm(@AuthenticationPrincipal AuthPrincipal me,
                                               @PathVariable String evaluationId) {
        return ApiResponse.success(evaluationService.confirm(me, evaluationId));
    }

    @Operation(summary = "이의 제기",
            description = "'확인 대기'일 때만 가능하다. 이의 항목(item_id 또는 항목 이름)으로 검토 요청을 만들어 담당 팀장에게 배정하고 "
                    + "바로 '검토 대기'로 바꾼다(202). 평가에 없는 항목이면 400.")
    @PostMapping("/{evaluationId}/appeals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<StatusResponse> appeal(@AuthenticationPrincipal AuthPrincipal me,
                                              @PathVariable String evaluationId,
                                              @Valid @RequestBody AppealRequest request) {
        return ApiResponse.success(evaluationService.appeal(me, evaluationId, request));
    }

    @Operation(summary = "실패·보류 평가 재처리", description = "'처리 실패' 또는 '자료 보류' 건을 다시 평가한다. 담당 팀장만(202).")
    @PostMapping("/{evaluationId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<StatusResponse> retry(@AuthenticationPrincipal AuthPrincipal me,
                                             @PathVariable String evaluationId) {
        return ApiResponse.success(evaluationService.retry(me, evaluationId));
    }
}
