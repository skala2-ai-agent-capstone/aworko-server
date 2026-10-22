package com.aworko.server.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI aworkoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aworko Server API")
                        .description("업무 기반 AI 활용 역량 평가·코칭 에이전트 백엔드. "
                                + "업무 완료 접수, 평가 조회·진행 상태(SSE), 본인 확인·이의 제기, 팀장 검토, 팀 집계")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
