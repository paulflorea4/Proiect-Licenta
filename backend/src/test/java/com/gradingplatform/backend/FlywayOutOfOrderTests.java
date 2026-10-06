package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 2.2a: why the `dev` profile enables Flyway's out-of-order mode. The probe migration it applies
 * changes the database, and Spring caches a test context (and so its Testcontainers database)
 * across every test class with the same configuration, including {@link FlywayDevProfileTests}.
 * `@DirtiesContext` discards this class's context afterwards, so no other class can see the probe,
 * whatever order the classes run in.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlywayOutOfOrderTests {

    private static final String SCHEMA = "classpath:db/migration";
    private static final String SEED = "classpath:db/dev-seed";
    private static final String PROBE = "classpath:db/out-of-order-probe";

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void aSchemaMigrationAddedAfterTheSeedStillAppliesInDev() {
        // Without out-of-order, a migration numbered below an already applied seed version
        // (here 999 < 1000) is rejected, which is what would break a long-lived dev database.
        Flyway strict = Flyway.configure()
                .dataSource(dataSource)
                .locations(SCHEMA, SEED, PROBE)
                .outOfOrder(false)
                .load();
        assertThatThrownBy(strict::migrate).isInstanceOf(FlywayValidateException.class);

        Flyway lenient = Flyway.configure()
                .dataSource(dataSource)
                .locations(SCHEMA, SEED, PROBE)
                .outOfOrder(true)
                .load();
        assertThat(lenient.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM out_of_order_probe", Integer.class))
                .isZero();
    }
}
