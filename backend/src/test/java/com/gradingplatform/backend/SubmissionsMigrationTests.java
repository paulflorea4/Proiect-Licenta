package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V7 (1.2g): the `submissions` table exists with the agreed constraints and defaults. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SubmissionsMigrationTests {

    private static final String INSERT_SUBMISSION = "INSERT INTO submissions"
            + " (assignment_id, student_id, language, source_code, attempt_no) VALUES (?, ?, ?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long assignmentId;
    private Long studentId;

    @BeforeEach
    void insertAssignmentAndStudent() {
        Long teacherId = insertUser("teacher@example.com", "TEACHER");
        studentId = insertUser("student@example.com", "STUDENT");
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

    private Long insertUser(String email, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'hash', 'Some Name', ?)"
                        + " RETURNING id",
                Long.class,
                email,
                role);
    }

    @Test
    void v7WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '7'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void newSubmissionIsQueuedWithSubmittedAtSetAndNoStartOrFinishTime() {
        jdbcTemplate.update(INSERT_SUBMISSION, assignmentId, studentId, "JAVA", "class Main {}", 1);

        assertThat(jdbcTemplate.queryForObject("SELECT id FROM submissions", Long.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM submissions", String.class))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT submitted_at FROM submissions", OffsetDateTime.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT started_at FROM submissions", OffsetDateTime.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT finished_at FROM submissions", OffsetDateTime.class))
                .isNull();
    }

    @Test
    void statusIsNotRestrictedByTheDatabase() {
        jdbcTemplate.update(INSERT_SUBMISSION, assignmentId, studentId, "JAVA", "class Main {}", 1);
        jdbcTemplate.update("UPDATE submissions SET status = 'SOME_FUTURE_STATUS'");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM submissions", String.class))
                .isEqualTo("SOME_FUTURE_STATUS");
    }

    @Test
    void attemptNumbersMustBeUniquePerStudentAndAssignment() {
        jdbcTemplate.update(INSERT_SUBMISSION, assignmentId, studentId, "JAVA", "class Main {}", 1);

        assertThatThrownBy(() ->
                        jdbcTemplate.update(INSERT_SUBMISSION, assignmentId, studentId, "JAVA", "class Main {}", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void successiveAttemptsAndOtherStudentsMayReuseAttemptNumbers() {
        Long otherStudentId = insertUser("other@example.com", "STUDENT");

        jdbcTemplate.update(INSERT_SUBMISSION, assignmentId, studentId, "JAVA", "v1", 1);
        jdbcTemplate.update(INSERT_SUBMISSION, assignmentId, studentId, "JAVA", "v2", 2);
        jdbcTemplate.update(INSERT_SUBMISSION, assignmentId, otherStudentId, "JAVA", "v1", 1);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void assignmentMustExist() {
        assertInsertRejected(-1L, studentId, "JAVA", "code", 1);
    }

    @Test
    void studentMustExist() {
        assertInsertRejected(assignmentId, -1L, "JAVA", "code", 1);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void assignmentIsRequired() {
        assertInsertRejected(null, studentId, "JAVA", "code", 1);
    }

    @Test
    void studentIsRequired() {
        assertInsertRejected(assignmentId, null, "JAVA", "code", 1);
    }

    @Test
    void languageIsRequired() {
        assertInsertRejected(assignmentId, studentId, null, "code", 1);
    }

    @Test
    void sourceCodeIsRequired() {
        assertInsertRejected(assignmentId, studentId, "JAVA", null, 1);
    }

    @Test
    void attemptNumberIsRequired() {
        assertInsertRejected(assignmentId, studentId, "JAVA", "code", null);
    }

    private void assertInsertRejected(
            Long assignment, Long student, String language, String sourceCode, Integer attemptNo) {
        assertThatThrownBy(() ->
                        jdbcTemplate.update(INSERT_SUBMISSION, assignment, student, language, sourceCode, attemptNo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
