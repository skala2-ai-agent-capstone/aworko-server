package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 내규 업로드 결과: 적재된 내규와 이어서 시작한 평가 기준 초안 생성 */
public record PolicyUploadResult(
        PolicyDocumentSaved document,
        @Schema(description = "같은 내용을 다시 올려(duplicate) 생성하지 않았으면 null") RubricGeneration generation) {
}
