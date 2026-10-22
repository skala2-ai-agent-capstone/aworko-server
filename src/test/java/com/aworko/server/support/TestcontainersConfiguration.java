package com.aworko.server.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 운영과 같은 순서를 재현한다: MCP 쪽 001_schema.sql이 먼저 적용된 DB 위에서 Flyway(V1)와 JPA validate가 돈다.
 * src/test/resources/db/001_schema.sql은 ai-agent-capstone/mcp_servers/db/postgres/001_schema.sql 사본이다.
 * 원본이 바뀌면 같이 갱신한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("axeval")
                .withUsername("axeval")
                .withPassword("axeval")
                .withInitScript("db/001_schema.sql");
    }
}
