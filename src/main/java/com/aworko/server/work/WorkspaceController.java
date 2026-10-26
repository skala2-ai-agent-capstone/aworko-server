package com.aworko.server.work;

import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.work.dto.ArtifactResponse;
import com.aworko.server.work.dto.ChatMessageResponse;
import com.aworko.server.work.dto.ChatTurnResponse;
import com.aworko.server.work.dto.CreateWorkForm;
import com.aworko.server.work.dto.CreateWorkRequest;
import com.aworko.server.work.dto.CustomerResponse;
import com.aworko.server.work.dto.FileUpload;
import com.aworko.server.work.dto.SaveArtifactRequest;
import com.aworko.server.work.dto.SendMessageRequest;
import com.aworko.server.work.dto.SourceDocumentForm;
import com.aworko.server.work.dto.SourceDocumentRequest;
import com.aworko.server.work.dto.SourceDocumentResponse;
import com.aworko.server.work.dto.SourceFile;
import com.aworko.server.work.dto.WorkDetail;
import com.aworko.server.work.dto.WorkStats;
import com.aworko.server.work.dto.WorkSummary;
import com.aworko.server.work.dto.WorkTypeInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Workspace", description = "AI 업무 공간: 업무·고객 자료·대화·산출물. 최종 완료는 POST /api/work-events")
@RestController
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceChatService chatService;

    @Operation(summary = "업무 유형", description = "유형별 산출물 이름과 「새 업무 생성」 등록 항목(registration key)")
    @GetMapping("/api/work-types")
    public ApiResponse<List<WorkTypeInfo>> workTypes() {
        return ApiResponse.success(WorkTypeCatalog.all());
    }

    @Operation(summary = "고객 목록", description = "업무를 만들 때 고를 고객(내 회사)")
    @GetMapping("/api/customers")
    public ApiResponse<List<CustomerResponse>> customers(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(workspaceService.customers(me));
    }

    @Operation(summary = "업무 목록", description = "scope=mine(기본): 내 업무, scope=team: 담당 팀 업무(팀장만). 최근 활동 순")
    @GetMapping("/api/works")
    public ApiResponse<List<WorkSummary>> list(@AuthenticationPrincipal AuthPrincipal me,
                                               @Parameter(description = "mine | team")
                                               @RequestParam(defaultValue = "mine") String scope) {
        return ApiResponse.success(workspaceService.list(me, scope));
    }

    @Operation(summary = "업무 현황(내 업무)", description = "단계별 업무 수, 자료 보류 수, 업무 유형별 이번 주·지난 주 평균 점수(10점 만점)")
    @GetMapping("/api/works/summary")
    public ApiResponse<WorkStats> summary(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(workspaceService.stats(me));
    }

    @Operation(summary = "새 업무 생성(JSON)", description = "고객·등록 항목은 선택. 고객 자료는 만든 뒤 따로 올리거나 multipart로 같이 보낸다.")
    @PostMapping(value = "/api/works", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkDetail> create(@AuthenticationPrincipal AuthPrincipal me,
                                          @Valid @RequestBody CreateWorkRequest request) {
        return ApiResponse.success(workspaceService.create(me, request, List.of()));
    }

    @Operation(summary = "새 업무 생성(파일 포함)",
            description = "「새 업무 생성」 모달 그대로: title, work_type(rfp·proposal·quote·reply), purpose(목적·요구사항), "
                    + "customer_id(선택), files(고객 자료 여러 개, 파일당 20MB). PDF·DOCX·XLSX·PPTX·텍스트는 내용을 꺼내 AI·평가가 읽는다",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = CreateWorkForm.class))))
    @PostMapping(value = "/api/works", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkDetail> createWithFiles(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Parameter(hidden = true) @RequestParam String title,
                                                   @Parameter(hidden = true) @RequestParam(name = "work_type", required = false)
                                                   String workType,
                                                   @Parameter(hidden = true) @RequestParam(name = "customer_id", required = false)
                                                   String customerId,
                                                   @Parameter(hidden = true) @RequestParam(required = false) String purpose,
                                                   @Parameter(hidden = true) @RequestPart(name = "files", required = false)
                                                   List<MultipartFile> files) {
        if (title.isBlank() || title.length() > 200 || (purpose != null && purpose.length() > 4000)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "업무명(200자)·목적·요구사항(4000자)을 확인해 주세요");
        }
        CreateWorkRequest request = new CreateWorkRequest(title, workType, customerId, purpose, null);
        return ApiResponse.success(workspaceService.create(me, request, uploads(files)));
    }

    @Operation(summary = "업무 상세", description = "등록 내용·고객 자료·사내 참고 자료·최근 산출물. 본인은 원문, 같은 팀 팀장은 마스킹본(읽기만)")
    @GetMapping("/api/works/{workId}")
    public ApiResponse<WorkDetail> detail(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String workId) {
        return ApiResponse.success(workspaceService.detail(me, workId));
    }

    @Operation(summary = "고객 자료 올리기(텍스트 JSON)", description = "본문 텍스트를 직접 보낼 때. 같은 파일 이름은 새 버전")
    @PostMapping(value = "/api/works/{workId}/source-documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SourceDocumentResponse> addSourceDocument(@AuthenticationPrincipal AuthPrincipal me,
                                                                 @PathVariable String workId,
                                                                 @Valid @RequestBody SourceDocumentRequest request) {
        return ApiResponse.success(workspaceService.addSourceDocument(me, workId, request));
    }

    @Operation(summary = "고객 자료 올리기(파일)",
            description = "multipart: file(20MB까지), doc_type(선택: rfp | rfp_correction | meeting_note | call_note). "
                    + "원본은 그대로 저장하고, 꺼낸 텍스트를 AI·평가가 읽는다(text_extracted=false면 못 읽는 형식)",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = SourceDocumentForm.class))))
    @PostMapping(value = "/api/works/{workId}/source-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SourceDocumentResponse> uploadSourceDocument(@AuthenticationPrincipal AuthPrincipal me,
                                                                    @PathVariable String workId,
                                                                    @Parameter(hidden = true) @RequestPart("file")
                                                                    MultipartFile file,
                                                                    @Parameter(hidden = true)
                                                                    @RequestParam(name = "doc_type", required = false)
                                                                    String docType) {
        return ApiResponse.success(workspaceService.addSourceDocument(me, workId, upload(file), docType, null));
    }

    @Operation(summary = "고객 자료 원본 내려받기", description = "담당자 본인만(원본은 마스킹할 수 없다)")
    @GetMapping("/api/works/{workId}/source-documents/{attachmentId}/file")
    public ResponseEntity<byte[]> downloadSourceDocument(@AuthenticationPrincipal AuthPrincipal me,
                                                         @PathVariable String workId, @PathVariable String attachmentId) {
        SourceFile file = workspaceService.sourceFile(me, workId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.data());
    }

    @Operation(summary = "대화 기록", description = "messages 뷰(기록 프록시가 저장한 LLM 호출을 펼친 것). 실패한 호출은 질문만 남는다")
    @GetMapping("/api/works/{workId}/messages")
    public ApiResponse<List<ChatMessageResponse>> messages(@AuthenticationPrincipal AuthPrincipal me,
                                                           @PathVariable String workId) {
        return ApiResponse.success(workspaceService.messages(me, workId));
    }

    @Operation(summary = "AI에게 메시지 보내기",
            description = "업무 등록 내용·고객 자료·지정한 사내 자료·이전 대화를 붙여 llm-proxy로 보내고 답변을 돌려준다(최대 수 분). "
                    + "대화 기록(seq)은 프록시가 저장한 뒤 GET .../messages에 나타난다. LLM에 닿지 못하면 503")
    @PostMapping("/api/works/{workId}/messages")
    public ApiResponse<ChatTurnResponse> send(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String workId,
                                              @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.success(chatService.send(me, workId, request.content()));
    }

    @Operation(summary = "산출물 저장(초안 저장)", description = "새 버전(v1, v2 …)으로 저장한다. 최종 완료는 이 버전으로 POST /api/work-events")
    @PostMapping("/api/works/{workId}/artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ArtifactResponse> saveArtifact(@AuthenticationPrincipal AuthPrincipal me,
                                                      @PathVariable String workId,
                                                      @Valid @RequestBody SaveArtifactRequest request) {
        return ApiResponse.success(workspaceService.saveArtifact(me, workId, request));
    }

    private static List<FileUpload> uploads(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(f -> !f.isEmpty()).map(WorkspaceController::upload).toList();
    }

    private static FileUpload upload(MultipartFile file) {
        try {
            return new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "파일을 읽지 못했습니다");
        }
    }
}
