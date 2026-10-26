package com.aworko.server.evaluation;

import com.aworko.server.evaluation.dto.ResolveReviewRequest;
import com.aworko.server.evaluation.dto.ReviewDetail;
import com.aworko.server.evaluation.dto.ReviewSummary;
import com.aworko.server.evaluation.dto.StatusResponse;
import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Review", description = "평가 검토 (팀장)")
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "내게 배정된 검토 목록", description = "팀장만. 평가 상태가 '검토 대기'인 건이 처리할 건이다.")
    @GetMapping
    public ApiResponse<List<ReviewSummary>> list(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(reviewService.listAssigned(me));
    }

    @Operation(summary = "검토 상세", description = "검토 요청·이의 내용·평가 리포트(원문 근거·루브릭 항목·코칭). 배정된 팀장만.")
    @GetMapping("/{reviewRequestId}")
    public ApiResponse<ReviewDetail> detail(@AuthenticationPrincipal AuthPrincipal me,
                                            @PathVariable String reviewRequestId) {
        return ApiResponse.success(reviewService.detail(me, reviewRequestId));
    }

    @Operation(summary = "검토 결정",
            description = "keep: 기존 결과 유지 → 확정 / override: new_scores(항목별 0~2, 보안 준수는 0·2)를 새 초안으로 반영 → "
                    + "'확인 대기'(직원 본인 확인) / hold: 자료 보류. AI 재평가는 하지 않는다. "
                    + "평가가 '검토 대기'일 때만 가능(그 외 409). override에 new_scores가 없거나 범위·항목이 틀리면 400.")
    @PostMapping("/{reviewRequestId}/resolve")
    public ApiResponse<StatusResponse> resolve(@AuthenticationPrincipal AuthPrincipal me,
                                               @PathVariable String reviewRequestId,
                                               @Valid @RequestBody ResolveReviewRequest request) {
        return ApiResponse.success(reviewService.resolve(me, reviewRequestId, request));
    }
}
