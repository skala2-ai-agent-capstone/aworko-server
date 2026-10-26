package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 초안 만들기·승인·폐기로 상태가 바뀐 기준 버전 하나 */
public record RubricVersionChange(
        @Schema(example = "quote") String workType,
        @Schema(example = "RUB-quote") String docId,
        @Schema(example = "v3") String version,
        @Schema(description = "초안 만들기: 복사한 현행 버전", example = "v2") String basedOnVersion,
        @Schema(description = "승인: 폐기로 바뀐 이전 현행 버전") List<String> retiredVersions) {
}
