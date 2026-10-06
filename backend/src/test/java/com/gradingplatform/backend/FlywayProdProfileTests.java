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

/** 2.2a: under the `prod` profile Flyway runs the schema migrations only, strictly in order. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("prod")
class FlywayProdProfileTests {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayRanAtStartupWithTheSchemaMigrationsOnly() {
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(location -> location.getPath()))
                .containsExactly("db/migration");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void noSeedDataIsLoaded() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class))
                .isZero();
    }

    @Test
    void migrationsMustStayInOrder() {
        assertThat(flyway.getConfiguration().isOutOfOrder()).isFalse();
    }
}
