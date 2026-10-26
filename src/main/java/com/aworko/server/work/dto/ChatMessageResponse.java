package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** messages 뷰(llm_exchanges를 펼친 것)의 한 줄 */
public record ChatMessageResponse(
        @Schema(example = "W-3F2A9C1D0B4E-M01") String messageId,
        @Schema(example = "1") int seq,
        @Schema(description = "user | assistant", example = "user") String role,
        @Schema(description = "본문. 담당자 본인은 원문, 팀장은 마스킹본") String content,
        OffsetDateTime createdAt) {
}
