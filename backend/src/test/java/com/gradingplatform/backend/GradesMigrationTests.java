package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V9 (1.2i): the `grades` and `criterion_scores` tables exist with the agreed constraints. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class GradesMigrationTests {

    private static final String INSERT_GRADE =
            "INSERT INTO grades (submission_id, total_score, max_score) VALUES (?, ?, ?)";
    private static final String INSERT_CRITERION_SCORE =
            "INSERT INTO criterion_scores (submission_id, criterion_id, score, max_score) VALUES (?, ?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long submissionId;
    private Long criterionId;

    @BeforeEach
    void insertSubmissionAndCriterion() {
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
        criterionId = jdbcTemplate.queryForObject(
                "INSERT INTO rubric_criteria (assignment_id, name, type, weight)"
                        + " VALUES (?, 'Correctness', 'TESTS', 100) RETURNING id",
                Long.class,
                assignmentId);
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
    void v9WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '9'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void gradeGetsGradedAtAndKeepsExactDecimals() {
        jdbcTemplate.update(INSERT_GRADE, submissionId, new BigDecimal("66.67"), new BigDecimal("100.00"));

        assertThat(jdbcTemplate.queryForObject("SELECT graded_at FROM grades", OffsetDateTime.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT total_score FROM grades", BigDecimal.class))
                .isEqualByComparingTo("66.67");
        assertThat(jdbcTemplate.queryForObject("SELECT max_score FROM grades", BigDecimal.class))
                .isEqualByComparingTo("100");
    }

    @Test
    void scoreColumnsAreNumericNotFloatingPoint() {
        for (String column : new String[] {"total_score", "max_score"}) {
            assertThat(dataType("grades", column)).isEqualTo("numeric");
        }
        for (String column : new String[] {"score", "max_score"}) {
            assertThat(dataType("criterion_scores", column)).isEqualTo("numeric");
        }
    }

    private String dataType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class,
                table,
                column);
    }

    @Test
    void aSubmissionHasAtMostOneGrade() {
        jdbcTemplate.update(INSERT_GRADE, submissionId, BigDecimal.TEN, BigDecimal.TEN);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_GRADE, submissionId, BigDecimal.ONE, BigDecimal.TEN))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeSubmissionMustExist() {
        assertGradeRejected(-1L, BigDecimal.ONE, BigDecimal.TEN);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void gradeSubmissionIsRequired() {
        assertGradeRejected(null, BigDecimal.ONE, BigDecimal.TEN);
    }

    @Test
    void gradeTotalScoreIsRequired() {
        assertGradeRejected(submissionId, null, BigDecimal.TEN);
    }

    @Test
    void gradeMaxScoreIsRequired() {
        assertGradeRejected(submissionId, BigDecimal.ONE, null);
    }

    @Test
    void criterionScoreCommentIsOptionalAndCanBeSet() {
        jdbcTemplate.update(INSERT_CRITERION_SCORE, submissionId, criterionId, new BigDecimal("7.5"), BigDecimal.TEN);

        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM criterion_scores", String.class))
                .isNull();

        jdbcTemplate.update("UPDATE criterion_scores SET comment = 'Readable, but long methods'");

        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM criterion_scores", String.class))
                .isEqualTo("Readable, but long methods");
        assertThat(jdbcTemplate.queryForObject("SELECT score FROM criterion_scores", BigDecimal.class))
                .isEqualByComparingTo("7.5");
    }

    @Test
    void aCriterionHasAtMostOneScorePerSubmission() {
        jdbcTemplate.update(INSERT_CRITERION_SCORE, submissionId, criterionId, BigDecimal.ONE, BigDecimal.TEN);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        INSERT_CRITERION_SCORE, submissionId, criterionId, new BigDecimal("2"), BigDecimal.TEN))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void criterionScoreSubmissionMustExist() {
        assertCriterionScoreRejected(-1L, criterionId, BigDecimal.ONE, BigDecimal.TEN);
    }

    @Test
    void criterionScoreCriterionMustExist() {
        assertCriterionScoreRejected(submissionId, -1L, BigDecimal.ONE, BigDecimal.TEN);
    }

    @Test
    void criterionScoreSubmissionIsRequired() {
        assertCriterionScoreRejected(null, criterionId, BigDecimal.ONE, BigDecimal.TEN);
    }

    @Test
    void criterionScoreCriterionIsRequired() {
        assertCriterionScoreRejected(submissionId, null, BigDecimal.ONE, BigDecimal.TEN);
    }

    @Test
    void criterionScoreScoreIsRequired() {
        assertCriterionScoreRejected(submissionId, criterionId, null, BigDecimal.TEN);
    }

    @Test
    void criterionScoreMaxScoreIsRequired() {
        assertCriterionScoreRejected(submissionId, criterionId, BigDecimal.ONE, null);
    }

    private void assertGradeRejected(Long submission, BigDecimal total, BigDecimal max) {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_GRADE, submission, total, max))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertCriterionScoreRejected(Long submission, Long criterion, BigDecimal score, BigDecimal max) {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_CRITERION_SCORE, submission, criterion, score, max))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
