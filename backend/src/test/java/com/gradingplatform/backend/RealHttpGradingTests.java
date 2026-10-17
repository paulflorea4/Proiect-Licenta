package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import com.gradingplatform.backend.sandbox.SandboxTestImages;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.3a: a submission accepted over HTTP is graded by the application's own worker and {@code
 * SubmissionGrader}, in real sandbox containers (Docker), and its results land in {@code
 * test_results}. Public and hidden tests are run and stored alike.
 */
class RealHttpGradingTests extends RealHttpTestBase {

    private static final Instant DEADLINE = Instant.parse("2031-06-30T21:59:59Z");
    private static final String SUM = "a, b = map(int, input().split())\nprint(a + b)\n";

    @Autowired
    private SandboxProperties languages;

    private User student;
    private Assignment assignment;
    private RubricCriterion criterion;

    @BeforeEach
    void world() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        User owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment =
                assignments.save(new Assignment(course.getId(), "Sum", "d", "PYTHON", DEADLINE, null, 2000, 128, null));
        criterion = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 100));
    }

    private long test(String input, String expected, TestVisibility visibility, int position) {
        return testCases
                .save(new TestCase(
                        assignment.getId(),
                        criterion.getId(),
                        "t" + position,
                        input,
                        expected,
                        visibility,
                        1,
                        position))
                .getId();
    }

    private long submitAndWaitForGrading(String code) throws Exception {
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
        // The programs here hold no quote, control character or non-ASCII text but these.
        String json = code.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        HttpResponse<String> response = post(
                "/assignments/" + assignment.getId() + "/submissions",
                "{\"sourceCode\":\"" + json + "\"}",
                tokenFor(student));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        long id = JsonPath.<Number>read(response.body(), "$.id").longValue();

        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (submissions.findById(id).orElseThrow().getStatus() != SubmissionStatus.GRADED) {
            assertThat(submissions.findById(id).orElseThrow().getStatus())
                    .as("still grading, or failed")
                    .isIn(SubmissionStatus.QUEUED, SubmissionStatus.RUNNING);
            assertThat(System.nanoTime()).as("graded within two minutes").isLessThan(deadline);
            Thread.sleep(100);
        }
        return id;
    }

    @Test
    void everyTestIsRunAndStoredPublicAndHiddenAlike() throws Exception {
        long right = test("1 2", "3", TestVisibility.PUBLIC, 1);
        long hidden = test("5 7", "12\n", TestVisibility.HIDDEN, 2);
        long wrong = test("2 2", "5", TestVisibility.PUBLIC, 3);

        long id = submitAndWaitForGrading(SUM);

        List<TestResult> rows = testResults.findBySubmissionIdOrderByIdAsc(id);
        assertThat(rows).extracting(TestResult::getTestCaseId).containsExactly(right, hidden, wrong);
        assertThat(rows)
                .extracting(TestResult::getStatus)
                .containsExactly(TestOutcome.PASSED, TestOutcome.PASSED, TestOutcome.FAILED);
        assertThat(rows).extracting(TestResult::getActualOutput).containsExactly("3\n", "12\n", "4\n");
        assertThat(rows).extracting(TestResult::getStderr).containsOnly("");
        assertThat(rows).extracting(TestResult::getRuntimeMs).doesNotContainNull();
        assertThat(submissions.findById(id).orElseThrow().getFinishedAt()).isNotNull();
    }

    @Test
    void aProgramThatCrashesIsStillGradedWithItsErrorStored() throws Exception {
        long first = test("1 0", "x", TestVisibility.PUBLIC, 1);
        long second = test("4 0", "y", TestVisibility.HIDDEN, 2);

        long id = submitAndWaitForGrading("a, b = map(int, input().split())\nprint(a // b)\n");

        List<TestResult> rows = testResults.findBySubmissionIdOrderByIdAsc(id);
        assertThat(rows).extracting(TestResult::getTestCaseId).containsExactly(first, second);
        assertThat(rows).extracting(TestResult::getStatus).containsOnly(TestOutcome.RUNTIME_ERROR);
        assertThat(rows).allSatisfy(row -> assertThat(row.getStderr()).contains("ZeroDivisionError"));
    }

    @Test
    void aNulPrintedByTheProgramIsStoredAndTheSubmissionStillGraded() throws Exception {
        test("", "ab", TestVisibility.PUBLIC, 1);

        long id = submitAndWaitForGrading("import sys\nsys.stdout.write('a\\x00b')\n");

        TestResult row = testResults.findBySubmissionIdOrderByIdAsc(id).get(0);
        assertThat(row.getStatus()).isEqualTo(TestOutcome.FAILED);
        assertThat(row.getActualOutput()).isEqualTo("a�b");
    }

    @Test
    void javaThatDoesNotCompileIsGradedWithEveryTestACompileError() throws Exception {
        SandboxTestImages.ensureBuilt(languages, "JAVA");
        jdbc.update("update assignments set language = 'JAVA' where id = ?", assignment.getId());
        long first = test("1 2", "3", TestVisibility.PUBLIC, 1);
        long second = test("5 7", "12", TestVisibility.HIDDEN, 2);

        long id = submitAndWaitForGrading("public class Main {\n    public static void main(String[] a) {\n"
                + "        System.out.println(1)\n    }\n}\n");

        List<TestResult> rows = testResults.findBySubmissionIdOrderByIdAsc(id);
        assertThat(rows).extracting(TestResult::getTestCaseId).containsExactly(first, second);
        assertThat(rows).extracting(TestResult::getStatus).containsOnly(TestOutcome.COMPILE_ERROR);
        assertThat(rows).extracting(TestResult::getActualOutput).containsOnlyNulls();
        assertThat(rows).extracting(TestResult::getRuntimeMs).containsOnlyNulls();
        assertThat(rows)
                .allSatisfy(
                        row -> assertThat(row.getStderr()).contains("Main.java").contains("error"));
        assertThat(rows.get(0).getStderr()).isEqualTo(rows.get(1).getStderr());
        assertThat(submissions.findById(id).orElseThrow().getStatus())
                .as("graded, not ERROR")
                .isEqualTo(SubmissionStatus.GRADED);
    }
}
