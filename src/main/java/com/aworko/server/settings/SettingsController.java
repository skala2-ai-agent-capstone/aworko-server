package com.aworko.server.settings;

import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.settings.dto.DocumentSummary;
import com.aworko.server.settings.dto.RubricDetail;
import com.aworko.server.settings.dto.RubricSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Settings", description = "운영 설정(팀장): 사내 자료·평가 기준 조회")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @Operation(summary = "사내 자료 목록", description = "references·policies와 기밀(색인 금지) 자료. 본문 제외")
    @GetMapping("/documents")
    public ApiResponse<List<DocumentSummary>> documents(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.success(settingsService.documents(me));
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
}
