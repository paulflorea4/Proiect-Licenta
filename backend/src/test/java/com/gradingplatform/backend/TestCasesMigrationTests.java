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

/** V6 (1.2f): the `test_cases` table exists with the agreed constraints and defaults. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class TestCasesMigrationTests {

    private static final String INSERT_TEST_CASE = "INSERT INTO test_cases"
            + " (assignment_id, criterion_id, name, input, expected_output, visibility, position)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long assignmentId;
    private Long criterionId;

    @BeforeEach
    void insertAssignmentAndCriterion() {
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
        criterionId = jdbcTemplate.queryForObject(
                "INSERT INTO rubric_criteria (assignment_id, name, type, weight)"
                        + " VALUES (?, 'Correctness', 'TESTS', 100) RETURNING id",
                Long.class,
                assignmentId);
    }

    @Test
    void v6WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '6'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void weightDefaultsToOne() {
        jdbcTemplate.update(INSERT_TEST_CASE, assignmentId, criterionId, "1 + 2", "1 2", "3", "PUBLIC", 1);

        assertThat(jdbcTemplate.queryForObject("SELECT id FROM test_cases", Long.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT weight FROM test_cases", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void criterionIsOptionalInTheDatabase() {
        jdbcTemplate.update(INSERT_TEST_CASE, assignmentId, null, "No criterion yet", "1 2", "3", "HIDDEN", 1);

        assertThat(jdbcTemplate.queryForObject("SELECT criterion_id FROM test_cases", Long.class))
                .isNull();
    }

    @Test
    void emptyInputAndExpectedOutputAreAllowed() {
        jdbcTemplate.update(INSERT_TEST_CASE, assignmentId, criterionId, "Prints nothing", "", "", "PUBLIC", 1);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_cases", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void visibilityIsNotRestrictedByTheDatabase() {
        jdbcTemplate.update(INSERT_TEST_CASE, assignmentId, criterionId, "Name", "in", "out", "OTHER", 1);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_cases", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void assignmentMustExist() {
        assertInsertRejected(-1L, criterionId, "Name", "in", "out", "PUBLIC", 1);
    }

    @Test
    void criterionMustExistWhenGiven() {
        assertInsertRejected(assignmentId, -1L, "Name", "in", "out", "PUBLIC", 1);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void assignmentIsRequired() {
        assertInsertRejected(null, criterionId, "Name", "in", "out", "PUBLIC", 1);
    }

    @Test
    void nameIsRequired() {
        assertInsertRejected(assignmentId, criterionId, null, "in", "out", "PUBLIC", 1);
    }

    @Test
    void inputIsRequired() {
        assertInsertRejected(assignmentId, criterionId, "Name", null, "out", "PUBLIC", 1);
    }

    @Test
    void expectedOutputIsRequired() {
        assertInsertRejected(assignmentId, criterionId, "Name", "in", null, "PUBLIC", 1);
    }

    @Test
    void visibilityIsRequired() {
        assertInsertRejected(assignmentId, criterionId, "Name", "in", "out", null, 1);
    }

    @Test
    void positionIsRequired() {
        assertInsertRejected(assignmentId, criterionId, "Name", "in", "out", "PUBLIC", null);
    }

    private void assertInsertRejected(
            Long assignment,
            Long criterion,
            String name,
            String input,
            String expectedOutput,
            String visibility,
            Integer position) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        INSERT_TEST_CASE, assignment, criterion, name, input, expectedOutput, visibility, position))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
