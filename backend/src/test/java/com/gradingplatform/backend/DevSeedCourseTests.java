package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 1.4b: the dev seed course, its enrollments, assignments, rubrics and tests. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/dev-seed")
class DevSeedCourseTests {

    private static final List<String> TITLES = List.of("Sum of Two Numbers", "Palindrome Check");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void seedMigrationWasApplied() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1001'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void oneCourseOwnedByTheTeacher() {
        List<String> owners = jdbcTemplate.queryForList(
                "SELECT u.email FROM courses c JOIN users u ON u.id = c.teacher_id", String.class);

        assertThat(owners).containsExactly("teacher@dev.example.com");
    }

    @Test
    void bothStudentsAreEnrolled() {
        List<String> students = jdbcTemplate.queryForList(
                "SELECT u.email FROM enrollments e JOIN users u ON u.id = e.student_id ORDER BY u.email", String.class);

        assertThat(students).containsExactly("student1@dev.example.com", "student2@dev.example.com");
    }

    @Test
    void twoPublishedAssignmentsOneJavaOnePython() {
        List<String> assignments = jdbcTemplate.queryForList(
                "SELECT title || ' ' || language || ' ' || published FROM assignments ORDER BY id", String.class);

        assertThat(assignments).containsExactly("Sum of Two Numbers JAVA true", "Palindrome Check PYTHON true");
    }

    @Test
    void javaAttemptsAreUnlimitedAndPythonAllowsThree() {
        assertThat(maxAttempts("JAVA")).isNull();
        assertThat(maxAttempts("PYTHON")).isEqualTo(3);
    }

    @Test
    void deadlinesAreInTheFuture() {
        Integer open =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assignments WHERE deadline > NOW()", Integer.class);

        assertThat(open).isEqualTo(2);
    }

    @Test
    void everyRubricSumsToOneHundred() {
        List<Integer> totals = jdbcTemplate.queryForList(
                "SELECT CAST(SUM(weight) AS INTEGER) FROM rubric_criteria GROUP BY assignment_id", Integer.class);

        assertThat(totals).containsExactly(100, 100);
    }

    @Test
    void javaRubricIsTestsPlusManual() {
        assertThat(criteriaOf("Sum of Two Numbers")).containsExactly("Correctness TESTS 80", "Code quality MANUAL 20");
    }

    @Test
    void pythonRubricHasTwoTestsCriteriaAndOneManual() {
        assertThat(criteriaOf("Palindrome Check"))
                .containsExactly("Basic cases TESTS 40", "Edge cases TESTS 40", "Readability MANUAL 20");
    }

    @Test
    void everyAssignmentHasBothPublicAndHiddenTests() {
        for (String title : TITLES) {
            List<String> visibilities = jdbcTemplate.queryForList(
                    "SELECT DISTINCT t.visibility FROM test_cases t JOIN assignments a ON a.id = t.assignment_id"
                            + " WHERE a.title = ?",
                    String.class,
                    title);

            assertThat(visibilities).as(title).containsExactlyInAnyOrder("PUBLIC", "HIDDEN");
        }
    }

    @Test
    void everyTestBelongsToATestsCriterionOfItsOwnAssignment() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_cases", Integer.class);
        Integer wellFormed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_cases t JOIN rubric_criteria c ON c.id = t.criterion_id"
                        + " WHERE c.assignment_id = t.assignment_id AND c.type = 'TESTS'",
                Integer.class);

        assertThat(total).isEqualTo(10);
        assertThat(wellFormed).isEqualTo(total);
    }

    @Test
    void testPositionsAreUniqueWithinAnAssignment() {
        Integer duplicates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM test_cases GROUP BY assignment_id, position"
                        + " HAVING COUNT(*) > 1) d",
                Integer.class);

        assertThat(duplicates).isZero();
    }

    @Test
    void javaAssignmentHasStarterCodeAndPythonDoesNot() {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT starter_code FROM assignments WHERE language = 'JAVA'", String.class))
                .contains("public class Main");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT starter_code FROM assignments WHERE language = 'PYTHON'", String.class))
                .isNull();
    }

    private Integer maxAttempts(String language) {
        return jdbcTemplate.queryForObject(
                "SELECT max_attempts FROM assignments WHERE language = ?", Integer.class, language);
    }

    private List<String> criteriaOf(String title) {
        return jdbcTemplate.queryForList(
                "SELECT c.name || ' ' || c.type || ' ' || c.weight FROM rubric_criteria c"
                        + " JOIN assignments a ON a.id = c.assignment_id WHERE a.title = ? ORDER BY c.id",
                String.class,
                title);
    }
}
