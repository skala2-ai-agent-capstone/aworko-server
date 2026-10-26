package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.springframework.web.multipart.MultipartFile;

/** 내규 올리기 multipart 폼 칸(Swagger 문서용) */
public record PolicyUploadForm(
        @Schema(description = "내규 파일(.pdf 또는 .txt, 20MB까지). 제N조·별표 N. 형식이어야 조항으로 나뉜다",
                requiredMode = Schema.RequiredMode.REQUIRED) MultipartFile file,
        @Schema(description = "새 버전 번호. 이미 있는 버전이면 내용이 같을 때만 그대로 돌려준다",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "v1.2") String version,
        @Schema(description = "비우면 현행 내규 제목") String title,
        @Schema(description = "시행일(YYYY-MM-DD). 비우면 오늘") LocalDate effectiveFrom) {
}
