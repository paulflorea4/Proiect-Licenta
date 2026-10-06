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

/** V10 (1.3a): the `ai_feedback` table exists with the agreed constraints. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AiFeedbackMigrationTests {

    private static final String INSERT_FEEDBACK =
            "INSERT INTO ai_feedback (submission_id, hint_level, content) VALUES (?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long submissionId;

    @BeforeEach
    void insertSubmission() {
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
    void v10WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '10'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void hintIsNotReportedByDefault() {
        jdbcTemplate.update(INSERT_FEEDBACK, submissionId, 1, "Re-read the loop bounds.");

        assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM ai_feedback", Object.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT reported FROM ai_feedback", Boolean.class))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT report_reason FROM ai_feedback", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT reported_at FROM ai_feedback", Object.class))
                .isNull();
    }

    @Test
    void hintCanBeReportedWithReasonAndTime() {
        jdbcTemplate.update(INSERT_FEEDBACK, submissionId, 2, "Look at the off-by-one.");
        jdbcTemplate.update("UPDATE ai_feedback SET reported = TRUE, report_reason = 'Wrong', reported_at = NOW()");

        assertThat(jdbcTemplate.queryForObject("SELECT reported FROM ai_feedback", Boolean.class))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT report_reason FROM ai_feedback", String.class))
                .isEqualTo("Wrong");
        assertThat(jdbcTemplate.queryForObject("SELECT reported_at FROM ai_feedback", Object.class))
                .isNotNull();
    }

    @Test
    void allThreeLevelsCanExistForOneSubmission() {
        jdbcTemplate.update(INSERT_FEEDBACK, submissionId, 1, "nudge");
        jdbcTemplate.update(INSERT_FEEDBACK, submissionId, 2, "pointer");
        jdbcTemplate.update(INSERT_FEEDBACK, submissionId, 3, "explanation");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_feedback", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void aLevelExistsAtMostOncePerSubmission() {
        jdbcTemplate.update(INSERT_FEEDBACK, submissionId, 1, "nudge");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_FEEDBACK, submissionId, 1, "another nudge"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionMustExist() {
        assertInsertRejected(-1L, 1, "nudge");
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void submissionIsRequired() {
        assertInsertRejected(null, 1, "nudge");
    }

    @Test
    void hintLevelIsRequired() {
        assertInsertRejected(submissionId, null, "nudge");
    }

    @Test
    void contentIsRequired() {
        assertInsertRejected(submissionId, 1, null);
    }

    private void assertInsertRejected(Long submission, Integer level, String content) {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_FEEDBACK, submission, level, content))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
