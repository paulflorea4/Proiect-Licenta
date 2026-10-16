package com.gradingplatform.backend;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The only database tests ever use (2.7a): `@Import(TestcontainersConfiguration.class)` on a
 * `@SpringBootTest` gives that test's context its own empty database inside the one shared Postgres
 * container, see {@link SharedPostgres}. Spring Boot's datasource and Flyway pick up the connection
 * details bean ahead of any `spring.datasource.*` property or `SPRING_DATASOURCE_*` environment
 * variable, so a developer's own settings never reach a test.
 *
 * <p>It also makes a {@link MutableClock} the application's {@code Clock} (the system clock unless a
 * test sets it, 5.1c). That lives here, in the configuration every integration test already shares,
 * because each distinct test configuration is another cached context holding its own connection
 * pool, and the shared Postgres has a connection limit: a test's own {@code @Import} of the clock
 * once split one context into two and ran it out on CI.
 *
 * <p>Real-HTTP tests extend {@link RealHttpTestBase}, which already imports this.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(MutableClock.Config.class)
public class TestcontainersConfiguration {

    @Bean
    JdbcConnectionDetails testDatabase() {
        return SharedPostgres.newDatabase();
    }
}
