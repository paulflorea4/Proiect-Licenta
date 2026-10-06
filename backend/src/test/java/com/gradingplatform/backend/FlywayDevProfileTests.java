package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 2.2a: under the `dev` profile Flyway runs the schema migrations plus the dev-only seed data. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("dev")
class FlywayDevProfileTests {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayRanAtStartupWithTheSeedLocationOnThePath() {
        assertThat(locations()).containsExactlyInAnyOrder("db/migration", "db/dev-seed");
        assertThat(applied("SELECT COUNT(*) FROM flyway_schema_history WHERE success"))
                .isEqualTo(13);
    }

    @Test
    void seedDataIsPresent() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM grades", Integer.class))
                .isEqualTo(7);
    }

    @Test
    void outOfOrderIsEnabledOnlyForDev() {
        assertThat(flyway.getConfiguration().isOutOfOrder()).isTrue();
    }

    private String[] locations() {
        return Arrays.stream(flyway.getConfiguration().getLocations())
                .map(location -> location.getPath())
                .toArray(String[]::new);
    }

    private Integer applied(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
