package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 1.4a: the default Flyway configuration (no `dev` seed location) never loads seed data. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DevSeedNotInDefaultPathTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void noSeedMigrationIsApplied() {
        Integer seedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE CAST(version AS INTEGER) >= 1000", Integer.class);

        assertThat(seedMigrations).isZero();
    }

    @Test
    void noUsersExist() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class))
                .isZero();
    }
}
