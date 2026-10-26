package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 업무 등록 내용 한 칸. 화면에 이름(label)과 값을 그대로 보여 주면 된다 */
public record RegistrationItem(
        @Schema(example = "customer_purpose") String key,
        @Schema(example = "고객 목적") String label,
        @Schema(description = "본인은 원문, 팀장은 마스킹본") String value) {
}
