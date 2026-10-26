package com.aworko.server.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
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

    /** 스키마 칸 이름을 실제 요청·응답과 같게(spring.jackson.property-naming-strategy: SNAKE_CASE) */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }

    @Bean
    public OpenAPI aworkoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aworko Server API")
                        .description("업무 기반 AI 활용 역량 평가·코칭 에이전트 백엔드. 로그인, AI 업무 공간(업무·고객 자료·대화·산출물), "
                                + "실시간 피드백, 업무 완료 접수, 평가 조회·본인 확인·이의 제기, 팀장 검토, 팀 집계, 운영 설정. "
                                + "요청·응답 칸 이름은 모두 snake_case, 응답 봉투는 {ok, data} / {ok:false, error:{code, message}}")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
