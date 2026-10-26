package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record ArtifactResponse(
        @Schema(example = "ART-3F2A9C1D0B4E") String artifactId,
        @Schema(example = "v2") String version,
        @Schema(description = "ai_draft | employee", example = "employee") String author,
        OffsetDateTime savedAt,
        @Schema(description = "본문. 담당자 본인은 원문, 팀장은 마스킹본") String content) {
}
