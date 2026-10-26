package com.aworko.server.work;

import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.work.dto.AttachReferenceRequest;
import com.aworko.server.work.dto.InternalReference;
import com.aworko.server.work.dto.ReferenceCandidate;
import com.aworko.server.work.dto.ReferenceDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Workspace", description = "AI 업무 공간: 업무·고객 자료·대화·산출물. 최종 완료는 POST /api/work-events")
@RestController
@RequiredArgsConstructor
public class WorkReferenceController {

    private final WorkReferenceService referenceService;

    @Operation(summary = "사내 자료 목록",
            description = "업무에 지정할 수 있는 내 회사 사내 자료(제품·가격 자료, 내규). 기밀(색인 안 함)은 빠진다. "
                    + "폐기·초안 버전도 status와 함께 나온다(현행 버전을 고르는 것도 평가 대상)")
    @GetMapping("/api/references")
    public ApiResponse<List<ReferenceCandidate>> candidates(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(referenceService.candidates(me));
    }

    @Operation(summary = "사내 자료 지정",
            description = "담당자 본인만. 지정한 자료는 업무 상세 internal_references(source=attached)에 나오고, "
                    + "AI 대화에 본문이 함께 전달되며, 최종 완료 때 평가 스냅샷에 들어간다. 이미 지정했으면 그대로 돌려준다")
    @PostMapping("/api/works/{workId}/references")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InternalReference> attach(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String workId,
                                                 @Valid @RequestBody AttachReferenceRequest request) {
        return ApiResponse.success(referenceService.attach(me, workId, request.docId(), request.version()));
    }

    @Operation(summary = "사내 자료 지정 해제", description = "담당자 본인만. 지정되지 않은 자료면 404")
    @DeleteMapping("/api/works/{workId}/references/{docId}/{version}")
    public ApiResponse<Void> detach(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String workId,
                                    @PathVariable String docId, @PathVariable String version) {
        referenceService.detach(me, workId, docId, version);
        return ApiResponse.success();
    }

    @Operation(summary = "사내 자료 열람",
            description = "본문을 돌려준다. 담당자 본인이 열면 열람 기록(reference_access_logs)이 남아 "
                    + "업무 상세 internal_references(source=accessed)와 평가(결과 검증)에 쓰인다. 같은 팀 팀장은 기록 없이 읽기만")
    @GetMapping("/api/works/{workId}/references/{docId}/{version}")
    public ApiResponse<ReferenceDocument> read(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String workId,
                                               @PathVariable String docId, @PathVariable String version) {
        return ApiResponse.success(referenceService.read(me, workId, docId, version));
    }
}
