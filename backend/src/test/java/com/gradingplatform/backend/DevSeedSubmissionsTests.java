package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 1.4c: the dev seed submissions, test results, grades and criterion scores. Expected totals are
 * the hand-computed ones from the V1002 header, not derived from the data.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/dev-seed")
class DevSeedSubmissionsTests {

    private static final String SUBMISSION_LABEL = "u.email || ' | ' || a.title || ' | #' || s.attempt_no";
    private static final String SUBMISSION_JOINS =
            " FROM submissions s JOIN users u ON u.id = s.student_id" + " JOIN assignments a ON a.id = s.assignment_id";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void seedMigrationWasApplied() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1002'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void sevenSubmissionsAllGraded() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class))
                .isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM submissions WHERE status = 'GRADED'"
                                + " AND started_at IS NOT NULL AND finished_at IS NOT NULL",
                        Integer.class))
                .isEqualTo(7);
    }

    @Test
    void totalsMatchTheHandComputedValues() {
        List<String> totals = jdbcTemplate.queryForList(
                "SELECT " + SUBMISSION_LABEL + " || ' = ' || g.total_score" + SUBMISSION_JOINS
                        + " JOIN grades g ON g.submission_id = s.id ORDER BY s.id",
                String.class);

        assertThat(totals)
                .containsExactlyInAnyOrder(
                        "student1@dev.example.com | Sum of Two Numbers | #1 = 0.00",
                        "student1@dev.example.com | Sum of Two Numbers | #2 = 68.33",
                        "student2@dev.example.com | Sum of Two Numbers | #1 = 100.00",
                        "student1@dev.example.com | Palindrome Check | #1 = 0.00",
                        "student1@dev.example.com | Palindrome Check | #2 = 53.33",
                        "student2@dev.example.com | Palindrome Check | #1 = 60.00",
                        "student2@dev.example.com | Palindrome Check | #2 = 98.00");
    }

    @Test
    void everyGradeIsOutOfOneHundredAndEqualsItsCriterionScores() {
        Integer mismatched = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM grades g WHERE g.max_score <> 100 OR g.total_score <>"
                        + " (SELECT COALESCE(SUM(cs.score), 0) FROM criterion_scores cs WHERE cs.submission_id = g.submission_id)",
                Integer.class);

        assertThat(mismatched).isZero();
    }

    @Test
    void criterionScoresStayWithinTheirWeight() {
        Integer outOfRange = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM criterion_scores cs JOIN rubric_criteria c ON c.id = cs.criterion_id"
                        + " WHERE cs.score < 0 OR cs.score > cs.max_score OR cs.max_score <> c.weight",
                Integer.class);

        assertThat(outOfRange).isZero();
    }

    @Test
    void criterionsBelongToTheirSubmissionsAssignment() {
        Integer foreign = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM criterion_scores cs JOIN submissions s ON s.id = cs.submission_id"
                        + " JOIN rubric_criteria c ON c.id = cs.criterion_id WHERE c.assignment_id <> s.assignment_id",
                Integer.class);

        assertThat(foreign).isZero();
    }

    @Test
    void manualCriteriaNotYetScoredHaveNoRowRatherThanAZero() {
        List<String> pending = jdbcTemplate.queryForList(
                "SELECT " + SUBMISSION_LABEL + " || ' | ' || c.name" + SUBMISSION_JOINS
                        + " JOIN rubric_criteria c ON c.assignment_id = s.assignment_id AND c.type = 'MANUAL'"
                        + " WHERE NOT EXISTS (SELECT 1 FROM criterion_scores cs"
                        + " WHERE cs.submission_id = s.id AND cs.criterion_id = c.id) ORDER BY s.id",
                String.class);

        assertThat(pending)
                .containsExactlyInAnyOrder(
                        "student1@dev.example.com | Sum of Two Numbers | #1 | Code quality",
                        "student1@dev.example.com | Palindrome Check | #1 | Readability",
                        "student1@dev.example.com | Palindrome Check | #2 | Readability",
                        "student2@dev.example.com | Palindrome Check | #1 | Readability");
    }

    @Test
    void scoredManualCriteriaCarryATeacherComment() {
        List<String> commented = jdbcTemplate.queryForList(
                "SELECT c.name FROM criterion_scores cs JOIN rubric_criteria c ON c.id = cs.criterion_id"
                        + " WHERE c.type = 'MANUAL' AND cs.comment IS NOT NULL ORDER BY cs.id",
                String.class);

        assertThat(commented).containsExactlyInAnyOrder("Code quality", "Code quality", "Readability");
    }

    @Test
    void everySubmissionHasOneResultPerTestOfItsAssignment() {
        Integer incomplete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions s WHERE"
                        + " (SELECT COUNT(*) FROM test_results r WHERE r.submission_id = s.id)"
                        + " <> (SELECT COUNT(*) FROM test_cases t WHERE t.assignment_id = s.assignment_id)",
                Integer.class);

        assertThat(incomplete).isZero();
    }

    @Test
    void testResultsBelongToTestsOfTheSubmissionsAssignment() {
        Integer foreign = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_results r JOIN submissions s ON s.id = r.submission_id"
                        + " JOIN test_cases t ON t.id = r.test_case_id WHERE t.assignment_id <> s.assignment_id",
                Integer.class);

        assertThat(foreign).isZero();
    }

    @Test
    void testsCriterionScoresAreTheWeightedFractionOfPassedTests() {
        // Independent of the hand-written scores: recompute weight x passed weight / total weight
        // from the stored results and compare at the two-decimal scale the schema stores.
        List<BigDecimal> differences = jdbcTemplate.queryForList(
                "SELECT cs.score - ROUND(CAST(c.weight AS NUMERIC) * COALESCE(SUM(t.weight) FILTER (WHERE r.status = 'PASSED'), 0)"
                        + " / SUM(t.weight), 2)"
                        + " FROM criterion_scores cs JOIN rubric_criteria c ON c.id = cs.criterion_id"
                        + " JOIN test_cases t ON t.criterion_id = c.id"
                        + " JOIN test_results r ON r.test_case_id = t.id AND r.submission_id = cs.submission_id"
                        + " WHERE c.type = 'TESTS' GROUP BY cs.id, cs.score, c.weight",
                BigDecimal.class);

        assertThat(differences).isNotEmpty().allSatisfy(d -> assertThat(d).isEqualByComparingTo("0"));
    }

    @Test
    void scoresSpreadFromZeroToFullMarks() {
        assertThat(jdbcTemplate.queryForObject("SELECT MIN(total_score) FROM grades", BigDecimal.class))
                .isEqualByComparingTo("0");
        assertThat(jdbcTemplate.queryForObject("SELECT MAX(total_score) FROM grades", BigDecimal.class))
                .isEqualByComparingTo("100");
    }

    @Test
    void allTestResultStatusesTheSeedPromisesArePresent() {
        List<String> statuses =
                jdbcTemplate.queryForList("SELECT DISTINCT status FROM test_results ORDER BY status", String.class);

        assertThat(statuses).containsExactly("COMPILE_ERROR", "FAILED", "PASSED", "RUNTIME_ERROR", "TIMEOUT");
    }

    @Test
    void oneSubmissionIsACompileError() {
        List<String> compileErrors = jdbcTemplate.queryForList(
                "SELECT DISTINCT " + SUBMISSION_LABEL + SUBMISSION_JOINS
                        + " JOIN test_results r ON r.submission_id = s.id WHERE r.status = 'COMPILE_ERROR'",
                String.class);

        assertThat(compileErrors).containsExactly("student1@dev.example.com | Sum of Two Numbers | #1");
    }

    @Test
    void attemptsStayWithinTheAssignmentLimitAndLanguageMatches() {
        Integer violations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions s JOIN assignments a ON a.id = s.assignment_id"
                        + " WHERE s.language <> a.language OR s.submitted_at > a.deadline"
                        + " OR (a.max_attempts IS NOT NULL AND s.attempt_no > a.max_attempts)",
                Integer.class);

        assertThat(violations).isZero();
    }

    @Test
    void submittersAreEnrolledStudents() {
        Integer notEnrolled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions s JOIN assignments a ON a.id = s.assignment_id"
                        + " WHERE NOT EXISTS (SELECT 1 FROM enrollments e"
                        + " WHERE e.course_id = a.course_id AND e.student_id = s.student_id)",
                Integer.class);

        assertThat(notEnrolled).isZero();
    }
}
