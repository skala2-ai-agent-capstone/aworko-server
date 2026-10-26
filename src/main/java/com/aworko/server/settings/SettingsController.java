package com.aworko.server.settings;

import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.settings.dto.DocumentSummary;
import com.aworko.server.settings.dto.PolicyClause;
import com.aworko.server.settings.dto.PolicyUploadForm;
import com.aworko.server.settings.dto.PolicyUploadResult;
import com.aworko.server.settings.dto.RubricDetail;
import com.aworko.server.settings.dto.RubricGeneration;
import com.aworko.server.settings.dto.RubricItemSaved;
import com.aworko.server.settings.dto.RubricItemUpdate;
import com.aworko.server.settings.dto.RubricSummary;
import com.aworko.server.settings.dto.RubricTargets;
import com.aworko.server.settings.dto.RubricVersionChange;
import com.aworko.server.work.dto.FileUpload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Settings", description = "운영 설정(팀장): 사내 자료 조회·내규 올리기, 평가 기준 초안 생성·수정·승인")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final RubricGenerationService rubricGenerationService;

    @Operation(summary = "사내 자료 목록", description = "references·policies와 기밀(색인 금지) 자료. 본문 제외")
    @GetMapping("/documents")
    public ApiResponse<List<DocumentSummary>> documents(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(settingsService.documents(me));
    }

    @Operation(summary = "내규 올리기",
            description = "multipart: file(.pdf|.txt), version, title(선택), effective_from(선택). 새 버전을 바로 현행으로 적재하고"
                    + "(이전 현행은 폐기) 4개 업무 유형의 평가 기준 초안 생성을 시작한다(generation.status=대기). "
                    + "초안은 승인해야 현행이 된다. 필요한 조항이 없거나 형식이 틀리면 400, 이미 있는 버전에 다른 내용이면 409, "
                    + "생성이 진행 중이면 409. 같은 버전·같은 내용이면 duplicate=true이고 생성하지 않는다",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = PolicyUploadForm.class))))
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PolicyUploadResult> uploadPolicy(@AuthenticationPrincipal AuthPrincipal me,
                                                        @Parameter(hidden = true) @RequestPart("file") MultipartFile file,
                                                        @Parameter(hidden = true) @RequestParam(required = false)
                                                        String version,
                                                        @Parameter(hidden = true) @RequestParam(required = false)
                                                        String title,
                                                        @Parameter(hidden = true)
                                                        @RequestParam(name = "effective_from", required = false)
                                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom) {
        return ApiResponse.success(rubricGenerationService.upload(me, upload(file), version, title, effectiveFrom));
    }

    @Operation(summary = "최근 평가 기준 초안 생성", description = "대기 | 완료(drafts) | 실패(error, error_message). "
            + "화면은 대기인 동안 폴링한다. 오래 대기 중이면 실패(TIMED_OUT)로 보인다. 생성한 적이 없으면 data 없음")
    @GetMapping("/rubric-generations/latest")
    public ApiResponse<RubricGeneration> latestGeneration(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(rubricGenerationService.latest(me));
    }

    @Operation(summary = "평가 기준 초안 다시 생성", description = "현행 내규로 4개 유형 초안을 다시 만든다(실패·시간 초과 뒤). "
            + "Agent가 저장하면 기존 초안(관리자 수정 포함)을 덮어쓴다. 진행 중이면 409, 현행 내규가 없으면 404")
    @PostMapping("/rubric-generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<RubricGeneration> regenerate(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(rubricGenerationService.regenerate(me));
    }

    @Operation(summary = "현행 내규 조항", description = "평가 기준 related_clauses로 고를 수 있는 조항과 첫 단락")
    @GetMapping("/policy-clauses")
    public ApiResponse<List<PolicyClause>> policyClauses(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(settingsService.policyClauses(me));
    }

    @Operation(summary = "평가 기준 버전 목록", description = "업무 유형별 루브릭 버전(현행·폐기·초안)")
    @GetMapping("/rubrics")
    public ApiResponse<List<RubricSummary>> rubrics(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(settingsService.rubrics(me));
    }

    @Operation(summary = "평가 기준 상세", description = "루브릭 한 버전의 평가 항목(점수 기준·미관측·검토 필요 조건·근거 자료)")
    @GetMapping("/rubrics/{docId}/{version}")
    public ApiResponse<RubricDetail> rubric(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String docId,
                                            @PathVariable String version) {
        return ApiResponse.success(settingsService.rubric(me, docId, version));
    }

    @Operation(summary = "수정 초안 만들기", description = "현행 기준을 그대로 복사한 초안(AI 없이). work_types를 비우면 4개 유형 모두. "
            + "요청한 유형에 초안이 이미 있으면 409, 현행이 없으면 404이고 아무것도 만들지 않는다")
    @PostMapping("/rubrics/drafts")
    public ApiResponse<List<RubricVersionChange>> createDrafts(@AuthenticationPrincipal AuthPrincipal me,
                                                               @Valid @RequestBody(required = false) RubricTargets request) {
        return ApiResponse.success(settingsService.createDrafts(me, request));
    }

    @Operation(summary = "초안 항목 저장", description = "초안 항목 하나에서 보낸 칸만 고친다. 현행·폐기 버전이면 409. "
            + "빈 칸·보안 준수 1점·현행 내규에 없는 조항·민감정보는 400(MCP 검사 메시지). "
            + "되돌리기는 현행 버전 항목의 값을 그대로 보내면 된다")
    @PatchMapping("/rubrics/{docId}/{version}/items/{itemId}")
    public ApiResponse<RubricItemSaved> updateItem(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String docId,
                                                   @PathVariable String version, @PathVariable String itemId,
                                                   @Valid @RequestBody RubricItemUpdate request) {
        return ApiResponse.success(settingsService.updateItem(me, docId, version, itemId, request));
    }

    @Operation(summary = "초안 승인 후 적용", description = "초안을 현행으로, 같은 기준의 이전 현행을 폐기로 바꾼다. 다음 평가부터 적용. "
            + "work_types를 비우면 회사의 모든 초안. 초안이 없으면 404")
    @PostMapping("/rubrics/approve")
    public ApiResponse<List<RubricVersionChange>> approve(@AuthenticationPrincipal AuthPrincipal me,
                                                          @Valid @RequestBody(required = false) RubricTargets request) {
        return ApiResponse.success(settingsService.approve(me, request));
    }

    @Operation(summary = "초안 폐기", description = "초안을 지운다(현행은 그대로). work_types를 비우면 회사의 모든 초안. 초안이 없으면 404")
    @PostMapping("/rubrics/discard")
    public ApiResponse<List<RubricVersionChange>> discard(@AuthenticationPrincipal AuthPrincipal me,
                                                          @Valid @RequestBody(required = false) RubricTargets request) {
        return ApiResponse.success(settingsService.discard(me, request));
    }

    private static FileUpload upload(MultipartFile file) {
        try {
            return new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "파일을 읽지 못했습니다");
        }
    }
}
