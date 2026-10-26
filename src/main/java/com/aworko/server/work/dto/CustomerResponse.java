package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CustomerResponse(
        @Schema(example = "CU-HANBIT-PHARMA") String customerId,
        @Schema(example = "한빛제약(가상)") String name) {
}
