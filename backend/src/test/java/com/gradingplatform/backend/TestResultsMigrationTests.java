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

/** V8 (1.2h): the `test_results` table exists with the agreed constraints. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class TestResultsMigrationTests {

    private static final String INSERT_RESULT =
            "INSERT INTO test_results (submission_id, test_case_id, status) VALUES (?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long submissionId;
    private Long testCaseId;

    @BeforeEach
    void insertSubmissionAndTestCase() {
        Long teacherId = insertUser("teacher@example.com", "TEACHER");
        Long studentId = insertUser("student@example.com", "STUDENT");
        Long courseId = jdbcTemplate.queryForObject(
                "INSERT INTO courses (title, teacher_id, enroll_code) VALUES ('Algorithms', ?, 'ABC234') RETURNING id",
                Long.class,
                teacherId);
        Long assignmentId = jdbcTemplate.queryForObject(
                "INSERT INTO assignments (course_id, title, description, language, deadline,"
                        + " time_limit_ms, memory_limit_mb)"
                        + " VALUES (?, 'Sum', 'Add two numbers', 'JAVA', NOW() + INTERVAL '7 days', 2000, 256)"
                        + " RETURNING id",
                Long.class,
                courseId);
        submissionId = jdbcTemplate.queryForObject(
                "INSERT INTO submissions (assignment_id, student_id, language, source_code, attempt_no)"
                        + " VALUES (?, ?, 'JAVA', 'class Main {}', 1) RETURNING id",
                Long.class,
                assignmentId,
                studentId);
        testCaseId = insertTestCase(assignmentId, "1 + 2");
    }

    private Long insertUser(String email, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'hash', 'Some Name', ?)"
                        + " RETURNING id",
                Long.class,
                email,
                role);
    }

    private Long insertTestCase(Long assignmentId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO test_cases (assignment_id, name, input, expected_output, visibility, position)"
                        + " VALUES (?, ?, '1 2', '3', 'PUBLIC', 1) RETURNING id",
                Long.class,
                assignmentId,
                name);
    }

    @Test
    void v8WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '8'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void resultOnlyNeedsSubmissionTestCaseAndStatus() {
        jdbcTemplate.update(INSERT_RESULT, submissionId, testCaseId, "COMPILE_ERROR");

        assertThat(jdbcTemplate.queryForObject("SELECT id FROM test_results", Long.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT actual_output FROM test_results", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT stderr FROM test_results", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT runtime_ms FROM test_results", Integer.class))
                .isNull();
    }

    @Test
    void outputStderrAndRuntimeCanBeStored() {
        jdbcTemplate.update(INSERT_RESULT, submissionId, testCaseId, "PASSED");
        jdbcTemplate.update("UPDATE test_results SET actual_output = '3', stderr = 'warn', runtime_ms = 42");

        assertThat(jdbcTemplate.queryForObject("SELECT actual_output FROM test_results", String.class))
                .isEqualTo("3");
        assertThat(jdbcTemplate.queryForObject("SELECT stderr FROM test_results", String.class))
                .isEqualTo("warn");
        assertThat(jdbcTemplate.queryForObject("SELECT runtime_ms FROM test_results", Integer.class))
                .isEqualTo(42);
    }

    @Test
    void longestStatusFits() {
        jdbcTemplate.update(INSERT_RESULT, submissionId, testCaseId, "RUNTIME_ERROR");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM test_results", String.class))
                .isEqualTo("RUNTIME_ERROR");
    }

    @Test
    void aTestHasAtMostOneResultPerSubmission() {
        jdbcTemplate.update(INSERT_RESULT, submissionId, testCaseId, "PASSED");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_RESULT, submissionId, testCaseId, "FAILED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionMustExist() {
        assertInsertRejected(-1L, testCaseId, "PASSED");
    }

    @Test
    void testCaseMustExist() {
        assertInsertRejected(submissionId, -1L, "PASSED");
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void submissionIsRequired() {
        assertInsertRejected(null, testCaseId, "PASSED");
    }

    @Test
    void testCaseIsRequired() {
        assertInsertRejected(submissionId, null, "PASSED");
    }

    @Test
    void statusIsRequired() {
        assertInsertRejected(submissionId, testCaseId, null);
    }

    private void assertInsertRejected(Long submission, Long testCase, String status) {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_RESULT, submission, testCase, status))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
