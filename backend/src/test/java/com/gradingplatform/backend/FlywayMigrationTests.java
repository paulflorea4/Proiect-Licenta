package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves Flyway really runs on startup against a real Postgres (Spring Boot 4 does not run it
 * unless the Flyway starter is present), instead of assuming the autoconfiguration kicked in.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywayMigrationTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayRanOnStartupAndCreatedItsHistoryTable() {
        Boolean historyTableExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables"
                        + " WHERE table_schema = 'public' AND table_name = 'flyway_schema_history')",
                Boolean.class);

        assertThat(historyTableExists).isTrue();
    }
}
