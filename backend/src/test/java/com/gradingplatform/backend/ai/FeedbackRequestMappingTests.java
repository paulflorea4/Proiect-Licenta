package com.gradingplatform.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.ai.FeedbackRequest.HiddenTestResult;
import com.gradingplatform.backend.ai.FeedbackRequest.PublicTestResult;
import com.gradingplatform.backend.dto.StudentSubmissionResponse;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionViewService.SubmissionDetails;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 7.1b: the AI service's request is built from the student's own view of their submission, so a
 * hidden test reaches it as whether it passed and nothing else. The tests start from entities as
 * stored, with every hidden detail a marker, go through the student view (5.5a) and look for the
 * markers in the request as it would be sent.
 */
class FeedbackRequestMappingTests {

    private static final String HIDDEN = "HIDDEN-DETAIL-";
    private static final String DESCRIPTION = "Read two integers and print their sum.";
    private static final String CODE = "a, b = map(int, input().split())\n\tprint(a * b)  # ``` kept as is\n";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<TestCase> tests = new ArrayList<>();
    private final List<TestResult> results = new ArrayList<>();
    private long nextId = 100;

    // ---- the request ----

    @Test
    void publicTestsGoInFullAndHiddenTestsAsPassedOnlyInRunOrder() {
        publicTest("small numbers", "2 3\n", "5", 1, TestOutcome.FAILED, "6\n", "");
        hiddenTest(2, TestOutcome.PASSED);
        publicTest("zeros", "0 0\n", "0", 3, TestOutcome.PASSED, "0\n", "");
        hiddenTest(4, TestOutcome.RUNTIME_ERROR);
        hiddenTest(5, TestOutcome.FAILED);

        FeedbackRequest request = FeedbackRequest.of(view(SubmissionStatus.GRADED), DESCRIPTION, 2);

        assertThat(request.publicTests())
                .containsExactly(
                        new PublicTestResult("small numbers", "2 3\n", "5", TestOutcome.FAILED, "6\n", ""),
                        new PublicTestResult("zeros", "0 0\n", "0", TestOutcome.PASSED, "0\n", ""));
        assertThat(request.hiddenTests())
                .containsExactly(new HiddenTestResult(true), new HiddenTestResult(false), new HiddenTestResult(false));
    }

    @Test
    void noHiddenDetailIsInTheRequestAsItWouldBeSent() {
        publicTest("small numbers", "2 3\n", "5", 1, TestOutcome.FAILED, "6\n", "");
        hiddenTest(2, TestOutcome.PASSED);
        hiddenTest(3, TestOutcome.RUNTIME_ERROR);

        String sent = JSON.writeValueAsString(FeedbackRequest.of(view(SubmissionStatus.GRADED), DESCRIPTION, 3));

        assertThat(sent).doesNotContain(HIDDEN).doesNotContain("Hidden test");
        assertThat(JSON.readTree(sent).get("hiddenTests").toString())
                .isEqualTo("[{\"passed\":true},{\"passed\":false}]");
    }

    @Test
    void theRestIsCopiedAsTheStudentSeesIt() {
        publicTest("small numbers", "2 3\n", "5", 1, TestOutcome.FAILED, "6\n", "warning\n");

        FeedbackRequest request = FeedbackRequest.of(view(SubmissionStatus.GRADED), DESCRIPTION, 1);

        assertThat(request.assignmentDescription()).isEqualTo(DESCRIPTION);
        assertThat(request.language()).isEqualTo("PYTHON");
        assertThat(request.code()).isEqualTo(CODE);
        assertThat(request.compilerOutput()).isNull();
        assertThat(request.hintLevel()).isEqualTo(1);
        assertThat(request.publicTests().get(0).stderr()).isEqualTo("warning\n");
    }

    @Test
    void aCompileErrorSendsTheCompilersMessageAndNoActualOutput() {
        // 5.3b: every row is COMPILE_ERROR with the compiler's text in stderr and no actual output.
        publicTest("small numbers", "2 3\n", "5", 1, TestOutcome.COMPILE_ERROR, null, "Main.java:3: error");
        hiddenTest(2, TestOutcome.COMPILE_ERROR);

        FeedbackRequest request = FeedbackRequest.of(view(SubmissionStatus.GRADED), DESCRIPTION, 2);

        assertThat(request.compilerOutput()).isEqualTo("Main.java:3: error");
        assertThat(request.publicTests().get(0).actualOutput()).isNull();
        assertThat(request.publicTests().get(0).status()).isEqualTo(TestOutcome.COMPILE_ERROR);
        assertThat(request.hiddenTests()).containsExactly(new HiddenTestResult(false));
    }

    @Test
    void anAssignmentWithoutTestsSendsNone() {
        FeedbackRequest request = FeedbackRequest.of(view(SubmissionStatus.GRADED), DESCRIPTION, 1);

        assertThat(request.publicTests()).isEmpty();
        assertThat(request.hiddenTests()).isEmpty();
    }

    // ---- refusals ----

    @ParameterizedTest
    @EnumSource(
            value = SubmissionStatus.class,
            names = {"GRADED"},
            mode = EnumSource.Mode.EXCLUDE)
    void aSubmissionThatIsNotGradedGetsNoRequest(SubmissionStatus status) {
        publicTest("small numbers", "2 3\n", "5", 1, TestOutcome.FAILED, "6\n", "");
        StudentSubmissionResponse notGraded = view(status);

        assertThatThrownBy(() -> FeedbackRequest.of(notGraded, DESCRIPTION, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, -1})
    void aLevelOutsideOneToThreeGetsNoRequest(int level) {
        StudentSubmissionResponse graded = view(SubmissionStatus.GRADED);

        assertThatThrownBy(() -> FeedbackRequest.of(graded, DESCRIPTION, level))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGradedPublicTestWithoutAResultIsRefusedRatherThanSentWithoutAStatus() {
        tests.add(testCase("small numbers", "2 3\n", "5", TestVisibility.PUBLIC, 1));
        hiddenTest(2, TestOutcome.PASSED); // one result exists, so the view lists the tests

        StudentSubmissionResponse graded = view(SubmissionStatus.GRADED);

        assertThatThrownBy(() -> FeedbackRequest.of(graded, DESCRIPTION, 1)).isInstanceOf(IllegalStateException.class);
    }

    // ---- nothing in the ai package reads the unmasked types ----

    @Test
    void noFileOfTheAiPackageReferencesTheTypesThatHoldHiddenTestsInFull() throws IOException {
        // The guard that keeps this mapping the only one, and built on the student view: a test case,
        // a stored result, the teacher's view and the service's raw details all hold hidden tests in full.
        Path ai = Path.of("src", "main", "java", "com", "gradingplatform", "backend", "ai");
        List<String> forbidden = List.of(
                "entity.TestCase",
                "entity.TestResult",
                "TeacherSubmissionResponse",
                "SubmissionDetails",
                "TestCaseResponse");
        try (Stream<Path> files = Files.list(ai)) {
            List<Path> sources =
                    files.filter(f -> f.toString().endsWith(".java")).toList();
            assertThat(sources).isNotEmpty();
            for (Path source : sources) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                assertThat(forbidden).as(source.toString()).noneMatch(text::contains);
            }
        }
    }

    // ---- helpers: entities as stored, then the student's view ----

    private void publicTest(
            String name,
            String input,
            String expected,
            int position,
            TestOutcome outcome,
            String actual,
            String stderr) {
        TestCase test = testCase(name, input, expected, TestVisibility.PUBLIC, position);
        tests.add(test);
        results.add(new TestResult(10L, test.getId(), outcome, actual, stderr, actual == null ? null : 12));
    }

    /** A hidden test whose every detail, and the program's output and stderr for it, is a marker. */
    private void hiddenTest(int position, TestOutcome outcome) {
        TestCase test = testCase(
                HIDDEN + "name-" + position,
                HIDDEN + "input-" + position,
                HIDDEN + "expected-" + position,
                TestVisibility.HIDDEN,
                position);
        tests.add(test);
        results.add(new TestResult(
                10L, test.getId(), outcome, HIDDEN + "actual-" + position, HIDDEN + "stderr-" + position, 12));
    }

    private TestCase testCase(String name, String input, String expected, TestVisibility visibility, int position) {
        TestCase test = new TestCase(1L, 7L, name, input, expected, visibility, 1, position);
        ReflectionTestUtils.setField(test, "id", nextId++);
        return test;
    }

    private StudentSubmissionResponse view(SubmissionStatus status) {
        Submission submission = new Submission(1L, 2L, "PYTHON", CODE, 1, Instant.EPOCH);
        ReflectionTestUtils.setField(submission, "id", 10L);
        ReflectionTestUtils.setField(submission, "status", status);
        return StudentSubmissionResponse.of(
                new SubmissionDetails(submission, tests, results, List.of(), List.of(), Optional.empty(), 0));
    }
}
