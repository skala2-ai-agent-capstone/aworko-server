package com.aworko.server.work;

import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Work", description = "업무 완료 접수")
@RestController
@RequiredArgsConstructor
public class WorkEventController {

    private final WorkEventService workEventService;

    @Operation(summary = "업무 완료 접수",
            description = "최종본 저장·업무 완료 시 호출한다. 평가 건을 만들고 평가 요청을 큐에 넣은 뒤 바로 202를 돌려준다. "
                    + "같은 업무·버전을 다시 보내면 기존 평가 건을 돌려준다(created=false). "
                    + "새 버전으로 다시 완료하는 것은 최근 평가가 평가 실패·자료 보류일 때만 되고, "
                    + "평가 중·확인 대기·검토 중·확정이면 409다.")
    @PostMapping("/api/work-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<WorkEventResponse> complete(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody WorkEventRequest request) {
        return ApiResponse.success(workEventService.complete(me, request));
    }
}
