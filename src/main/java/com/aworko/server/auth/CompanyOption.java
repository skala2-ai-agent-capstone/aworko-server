package com.aworko.server.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 가입 화면에서 고를 수 있는 회사와 그 팀 */
public record CompanyOption(
        @Schema(example = "C-LGCNS-SALES") String companyId,
        @Schema(description = "가입 요청의 company_name에 그대로 넣는다", example = "LG CNS 영업담당 (가상 테넌트)") String name,
        List<TeamOption> teams) {

    public record TeamOption(
            @Schema(example = "T-SALES-01") String teamId,
            @Schema(description = "가입 요청의 team_name에 그대로 넣는다", example = "영업1팀") String name) {
    }
}
