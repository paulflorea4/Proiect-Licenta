package com.gradingplatform.backend;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The only database tests ever use (2.7a): `@Import(TestcontainersConfiguration.class)` on a
 * `@SpringBootTest` gives that test's context its own empty database inside the one shared Postgres
 * container, see {@link SharedPostgres}. Spring Boot's datasource and Flyway pick up the connection
 * details bean ahead of any `spring.datasource.*` property or `SPRING_DATASOURCE_*` environment
 * variable, so a developer's own settings never reach a test.
 *
 * <p>Real-HTTP tests extend {@link RealHttpTestBase}, which already imports this.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    JdbcConnectionDetails testDatabase() {
        return SharedPostgres.newDatabase();
    }
}
