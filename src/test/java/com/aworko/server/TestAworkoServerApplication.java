package com.aworko.server;

import com.aworko.server.support.TestcontainersConfiguration;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * DB·RabbitMQ를 직접 띄우지 않고 백엔드를 실행한다: ./gradlew bootTestRun
 * Docker에 Postgres(MCP 스키마 적용)·RabbitMQ 컨테이너를 띄우고 테스트 계정을 넣은 뒤 서버를 시작한다.
 * 종료하면 컨테이너와 데이터도 사라진다. 로그인: 사번 1021(팀원) / 3001(팀장), 비밀번호 1234
 */
public class TestAworkoServerApplication {

    public static void main(String[] args) {
        SpringApplication.from(AworkoServerApplication::main)
                .with(TestcontainersConfiguration.class, DevSeed.class)
                .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DevSeed {

        /** Flyway가 끝난 뒤(컨텍스트 기동 후) 최소 시드 데이터를 넣는다. 여러 번 돌려도 같은 결과(ON CONFLICT). */
        @Bean
        ApplicationRunner seedDevData(DataSource dataSource) {
            return args -> new ResourceDatabasePopulator(new ClassPathResource("db/seed_minimal.sql"))
                    .execute(dataSource);
        }
    }
}
