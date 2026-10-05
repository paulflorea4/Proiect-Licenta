package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V5 (1.2e): the `rubric_criteria` table exists with the agreed constraints. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class RubricCriteriaMigrationTests {

    private static final String INSERT_CRITERION =
            "INSERT INTO rubric_criteria (assignment_id, name, type, weight) VALUES (?, ?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long assignmentId;

    @BeforeEach
    void insertAssignment() {
        Long teacherId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, role)"
                        + " VALUES ('teacher@example.com', 'hash', 'Teo Ionescu', 'TEACHER') RETURNING id",
                Long.class);
        Long courseId = jdbcTemplate.queryForObject(
                "INSERT INTO courses (title, teacher_id, enroll_code) VALUES ('Algorithms', ?, 'ABC234') RETURNING id",
                Long.class,
                teacherId);
        assignmentId = jdbcTemplate.queryForObject(
                "INSERT INTO assignments (course_id, title, description, language, deadline,"
                        + " time_limit_ms, memory_limit_mb)"
                        + " VALUES (?, 'Sum', 'Add two numbers', 'JAVA', NOW() + INTERVAL '7 days', 2000, 256)"
                        + " RETURNING id",
                Long.class,
                courseId);
    }

    @Test
    void v5WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '5'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void criterionGetsGeneratedIdAndConfigDefaultsToNull() {
        jdbcTemplate.update(INSERT_CRITERION, assignmentId, "Correctness", "TESTS", 90);

        assertThat(jdbcTemplate.queryForObject("SELECT id FROM rubric_criteria", Long.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT config FROM rubric_criteria", String.class))
                .isNull();
    }

    @Test
    void configIsJsonb() {
        jdbcTemplate.update(INSERT_CRITERION, assignmentId, "Style", "STATIC_ANALYSIS", 10);
        jdbcTemplate.update("UPDATE rubric_criteria SET config = '{\"maxComplexity\": 10}'::jsonb");

        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns"
                        + " WHERE table_name = 'rubric_criteria' AND column_name = 'config'",
                String.class);
        Integer maxComplexity = jdbcTemplate.queryForObject(
                "SELECT (config->>'maxComplexity')::int FROM rubric_criteria", Integer.class);

        assertThat(dataType).isEqualTo("jsonb");
        assertThat(maxComplexity).isEqualTo(10);
    }

    @Test
    void typeIsNotRestrictedByTheDatabase() {
        jdbcTemplate.update(INSERT_CRITERION, assignmentId, "Future", "SOME_FUTURE_TYPE", 5);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rubric_criteria", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void assignmentMustExist() {
        assertInsertRejected(-1L, "Correctness", "TESTS", 100);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void assignmentIsRequired() {
        assertInsertRejected(null, "Correctness", "TESTS", 100);
    }

    @Test
    void nameIsRequired() {
        assertInsertRejected(assignmentId, null, "TESTS", 100);
    }

    @Test
    void typeIsRequired() {
        assertInsertRejected(assignmentId, "Correctness", null, 100);
    }

    @Test
    void weightIsRequired() {
        assertInsertRejected(assignmentId, "Correctness", "TESTS", null);
    }

    private void assertInsertRejected(Long assignment, String name, String type, Integer weight) {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_CRITERION, assignment, name, type, weight))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
