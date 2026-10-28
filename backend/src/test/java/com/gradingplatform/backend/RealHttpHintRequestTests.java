package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.ai.AiServiceClient;
import com.gradingplatform.backend.ai.AiServiceException;
import com.gradingplatform.backend.ai.FeedbackRequest;
import com.gradingplatform.backend.ai.FeedbackResponse;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionResultWriter;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 7.2a: `POST /submissions/{submissionId}/hints`, 7.2c: only while a test did not pass, and 7.3a: a
 * failed AI call is a 503 that changes nothing. The AI service is replaced by a mock, so these
 * tests see exactly what the backend sends it and decide what it answers.
 */
@ExtendWith(OutputCaptureExtension.class)
class RealHttpHintRequestTests extends RealHttpTestBase {

    private static final String[] SECRETS = {
        "SECRET-H-NAME", "SECRET-H-INPUT", "SECRET-H-EXPECTED", "SECRET-H-ACTUAL", "SECRET-H-STDERR"
    };
    private static final String DESCRIPTION = "Read two integers and print their sum.";

    @MockitoBean
    AiServiceClient ai;

    @Autowired
    private SubmissionResultWriter writer;

    @Autowired
    private DataSource dataSource;

    private User owner;
    private User student;
    private User classmate;
    private Assignment assignment;
    private TestCase p1;
    private TestCase h1;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "HINT2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                DESCRIPTION,
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
        RubricCriterion tests =
                rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 100));
        p1 = testCases.save(
                new TestCase(assignment.getId(), tests.getId(), "adds two", "1 2", "3", TestVisibility.PUBLIC, 1, 1));
        h1 = testCases.save(new TestCase(
                assignment.getId(), tests.getId(), SECRETS[0], SECRETS[1], SECRETS[2], TestVisibility.HIDDEN, 1, 2));
        reset(ai);
    }

    @AfterEach
    void noHintLeftBehind() {
        jdbc.update("delete from ai_feedback");
    }

    // ---- the next level, stored ----

    @Test
    void theFirstRequestGivesLevelOneAndStoresIt() throws Exception {
        long id = graded(student);
        answer("Look at the operator.");

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(response.body(), "$.level")).isEqualTo(1);
        assertThat(JsonPath.<String>read(response.body(), "$.content")).isEqualTo("Look at the operator.");
        assertThat(JsonPath.<Boolean>read(response.body(), "$.fallback")).isFalse();
        assertThat(JsonPath.<String>read(response.body(), "$.createdAt")).isNotNull();
        assertThat(JsonPath.<Integer>read(response.body(), "$.submissionId")).isEqualTo((int) id);
        assertThat(storedLevels(id)).containsExactly(1);
        assertThat(jdbc.queryForObject("select content from ai_feedback where submission_id = ?", String.class, id))
                .isEqualTo("Look at the operator.");
    }

    @Test
    void theRequestSentIsTheStudentsViewWithTheHiddenTestAsPassedOnly() throws Exception {
        long id = graded(student);
        answer("Look at the operator.");

        request(student, id);

        FeedbackRequest sent = sentRequests().get(0);
        assertThat(sent.assignmentDescription()).isEqualTo(DESCRIPTION);
        assertThat(sent.language()).isEqualTo("PYTHON");
        assertThat(sent.code()).isEqualTo("print(sum(map(int, input().split())))");
        assertThat(sent.hintLevel()).isEqualTo(1);
        assertThat(sent.publicTests())
                .extracting(FeedbackRequest.PublicTestResult::name)
                .containsExactly("adds two");
        assertThat(sent.hiddenTests()).containsExactly(new FeedbackRequest.HiddenTestResult(false));
        String json = JsonMapper.builder().build().writeValueAsString(sent);
        for (String secret : SECRETS) {
            assertThat(json).doesNotContain(secret);
        }
    }

    @Test
    void eachRequestGivesTheNextLevelAndAFourthIsRefused() throws Exception {
        long id = graded(student);
        when(ai.feedback(any()))
                .thenReturn(new FeedbackResponse("One.", false))
                .thenReturn(new FeedbackResponse("Two.", false))
                .thenReturn(new FeedbackResponse("Three.", false));

        for (int level = 1; level <= 3; level++) {
            HttpResponse<String> response = request(student, id);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(JsonPath.<Integer>read(response.body(), "$.level")).isEqualTo(level);
        }
        HttpResponse<String> fourth = request(student, id);

        assertThat(fourth.statusCode()).isEqualTo(409);
        assertThat(code(fourth)).isEqualTo("HINTS_USED_UP");
        assertThat(sentRequests()).extracting(FeedbackRequest::hintLevel).containsExactly(1, 2, 3);
        assertThat(storedLevels(id)).containsExactly(1, 2, 3);
    }

    @Test
    void eachSubmissionHasItsOwnLevels() throws Exception {
        long first = graded(student);
        long second = gradedAgain(student);
        answer("A hint.");

        request(student, first);
        request(student, first);
        HttpResponse<String> response = request(student, second);

        assertThat(JsonPath.<Integer>read(response.body(), "$.level")).isEqualTo(1);
        assertThat(storedLevels(first)).containsExactly(1, 2);
        assertThat(storedLevels(second)).containsExactly(1);
    }

    // ---- the fallback: shown, not stored (the human's decision) ----

    @Test
    void aFallbackHintIsShownButNotStoredSoTheLevelIsAskedForAgain() throws Exception {
        long id = graded(student);
        when(ai.feedback(any()))
                .thenReturn(new FeedbackResponse("Trace a failing test by hand.", true))
                .thenReturn(new FeedbackResponse("Look at the operator.", false));

        HttpResponse<String> fallback = request(student, id);

        assertThat(fallback.statusCode()).as(fallback.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(fallback.body(), "$.fallback")).isTrue();
        assertThat(JsonPath.<Integer>read(fallback.body(), "$.level")).isEqualTo(1);
        assertThat(JsonPath.<String>read(fallback.body(), "$.content")).isEqualTo("Trace a failing test by hand.");
        assertThat(JsonPath.<Object>read(fallback.body(), "$.createdAt")).isNull();
        assertThat(storedLevels(id)).isEmpty();

        HttpResponse<String> real = request(student, id);

        assertThat(real.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(real.body(), "$.level")).isEqualTo(1);
        assertThat(sentRequests()).extracting(FeedbackRequest::hintLevel).containsExactly(1, 1);
        assertThat(storedLevels(id)).containsExactly(1);
    }

    // ---- two requests at once ----

    @Test
    void twoRequestsForTheSameLevelAtOnceStoreOneRowAndBothGetIt() throws Exception {
        long id = graded(student);
        CountDownLatch bothAsked = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(ai.feedback(any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            bothAsked.countDown();
            // Both requests have read "no hint yet" before either stores one.
            assertThat(bothAsked.await(10, TimeUnit.SECONDS)).isTrue();
            return new FeedbackResponse("Answer " + call + ".", false);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> request(student, id)));
            }
            HttpResponse<String> a = futures.get(0).get(30, TimeUnit.SECONDS);
            HttpResponse<String> b = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(201, 200);
            assertThat(JsonPath.<String>read(a.body(), "$.content"))
                    .isEqualTo(JsonPath.<String>read(b.body(), "$.content"));
            assertThat(JsonPath.<Integer>read(a.body(), "$.level")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(b.body(), "$.level")).isEqualTo(1);
            assertThat(storedLevels(id)).containsExactly(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- refusals: nothing is asked, nothing is stored ----

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "ERROR"})
    void aSubmissionThatIsNotGradedGetsNoHint(String status) throws Exception {
        long id = submission(student, status);

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("HINT_NEEDS_GRADED_SUBMISSION");
        verify(ai, never()).feedback(any());
        assertThat(storedLevels(id)).isEmpty();
    }

    @Test
    void anotherStudentsSubmissionIsForbiddenAndNothingIsAsked() throws Exception {
        long id = graded(student);

        HttpResponse<String> response = request(classmate, id);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("ACCESS_DENIED");
        verify(ai, never()).feedback(any());
        assertThat(storedLevels(id)).isEmpty();
    }

    @Test
    void aMissingSubmissionIsNotFound() throws Exception {
        HttpResponse<String> response = request(student, 987654321L);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("SUBMISSION_NOT_FOUND");
        verify(ai, never()).feedback(any());
    }

    @Test
    void theCourseOwnerCannotAskForAStudentsHint() throws Exception {
        long id = graded(student);

        HttpResponse<String> response = request(owner, id);

        assertThat(response.statusCode()).isEqualTo(403);
        verify(ai, never()).feedback(any());
    }

    // ---- eligibility (7.2c): at least one test did not pass ----

    @Test
    void aSubmissionThatPassedEveryTestHasNothingToImprove() throws Exception {
        long id = graded(student, 1, TestOutcome.PASSED, TestOutcome.PASSED);

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("HINT_NOTHING_TO_IMPROVE");
        verify(ai, never()).feedback(any());
        assertThat(storedLevels(id)).isEmpty();
    }

    @Test
    void aLostManualMarkOnASubmissionThatPassedEveryTestStillHasNothingToImprove() throws Exception {
        RubricCriterion style =
                rubricCriteria.save(new RubricCriterion(assignment.getId(), "Style", CriterionType.MANUAL, 20));
        long id = graded(student, 1, TestOutcome.PASSED, TestOutcome.PASSED);
        HttpResponse<String> scored =
                send("PUT", "/submissions/" + id + "/manual-scores/" + style.getId(), tokenFor(owner), "{\"score\":0}");
        assertThat(scored.statusCode()).as(scored.body()).isEqualTo(200);
        assertThat(total(id)).isLessThan(maxScore(id));

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("HINT_NOTHING_TO_IMPROVE");
        verify(ai, never()).feedback(any());
    }

    @Test
    void aGradedSubmissionWithoutTestsHasNothingToImprove() throws Exception {
        Assignment essay = assignments.save(new Assignment(
                assignment.getCourseId(),
                "Essay",
                DESCRIPTION,
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", essay.getId());
        rubricCriteria.save(new RubricCriterion(essay.getId(), "Answer", CriterionType.MANUAL, 100));
        long id = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', 1, 'GRADED') returning id",
                Long.class,
                essay.getId(),
                student.getId());

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("HINT_NOTHING_TO_IMPROVE");
        verify(ai, never()).feedback(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "TIMEOUT", "MEMORY_LIMIT", "RUNTIME_ERROR"})
    void aPublicTestThatDidNotPassMakesItEligibleWhateverTheOutcome(String outcome) throws Exception {
        long id = graded(student, 1, TestOutcome.valueOf(outcome), TestOutcome.PASSED);
        answer("Look at the operator.");

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(storedLevels(id)).containsExactly(1);
    }

    @Test
    void aHiddenTestThatDidNotPassAloneMakesItEligible() throws Exception {
        long id = graded(student, 1, TestOutcome.PASSED, TestOutcome.FAILED);
        answer("Look at the operator.");

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(sentRequests().get(0).hiddenTests()).containsExactly(new FeedbackRequest.HiddenTestResult(false));
    }

    @Test
    void aCompileErrorMakesItEligible() throws Exception {
        long id = graded(student, 1, TestOutcome.COMPILE_ERROR, TestOutcome.COMPILE_ERROR);
        answer("Read the compiler's first message.");

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(storedLevels(id)).containsExactly(1);
    }

    // ---- the call and the grade ----

    @Test
    void theAiServiceIsCalledWithNoDatabaseTransactionOpen() throws Exception {
        long id = graded(student);
        List<Boolean> inTransaction = new ArrayList<>();
        when(ai.feedback(any())).thenAnswer(invocation -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return new FeedbackResponse("Look.", false);
        });

        request(student, id);

        assertThat(inTransaction).containsExactly(false);
    }

    @Test
    void theAiServiceIsCalledHoldingNoDatabaseConnection() throws Exception {
        // 7.7a: no transaction is not enough; with open-session-in-view on, the connection of the
        // request's first read stayed checked out through the call (1 active here, 0 with it off).
        long id = graded(student);
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Integer> active = new ArrayList<>();
        when(ai.feedback(any())).thenAnswer(invocation -> {
            active.add(pool.getActiveConnections());
            return new FeedbackResponse("Look.", false);
        });

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(active).containsExactly(0);
    }

    @Test
    void aHintNeverChangesTheGrade() throws Exception {
        long id = graded(student);
        BigDecimal before = total(id);
        answer("Look.");

        request(student, id);
        request(student, id);

        assertThat(total(id)).isEqualByComparingTo(before);
    }

    // ---- the AI service fails (7.3a): 503, nothing changed ----

    /** Every way {@link AiServiceClient} reports a failure (7.1a), each with its fixed message. */
    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of("down", new AiServiceException("The AI service answered HTTP 503", 503)),
                Arguments.of("error", new AiServiceException("The AI service answered HTTP 500", 500)),
                Arguments.of("bad gateway", new AiServiceException("The AI service answered HTTP 502", 502)),
                Arguments.of("token refused", new AiServiceException("The AI service answered HTTP 401", 401)),
                Arguments.of("forbidden", new AiServiceException("The AI service answered HTTP 403", 403)),
                Arguments.of(
                        "timeout",
                        new AiServiceException(
                                "The AI service did not answer in time", new ResourceAccessException("timed out"))),
                Arguments.of(
                        "unreachable",
                        new AiServiceException(
                                "The AI service could not be reached", new ResourceAccessException("refused"))),
                Arguments.of("unreadable", new AiServiceException("The AI service's answer could not be read", (Integer)
                        null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void aFailedCallIsA503ThatChangesNothing(String name, AiServiceException failure) throws Exception {
        long id = graded(student);
        String before = snapshot(id);
        when(ai.feedback(any())).thenThrow(failure);

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("HINTS_UNAVAILABLE");
        assertThat(JsonPath.<String>read(response.body(), "$.message"))
                .isEqualTo("Hints are unavailable right now; your submission and its grade are not affected."
                        + " Try again later");
        assertThat(response.body()).doesNotContain(failure.getMessage()).doesNotContain("HTTP");
        assertThat(snapshot(id)).isEqualTo(before);
        verify(ai, times(1)).feedback(any());
    }

    @Test
    void afterAFailureTheSameLevelIsAskedForAgain() throws Exception {
        long id = graded(student);
        answer("One.");
        request(student, id);
        when(ai.feedback(any()))
                .thenThrow(new AiServiceException("The AI service answered HTTP 503", 503))
                .thenReturn(new FeedbackResponse("Two.", false));

        HttpResponse<String> failed = request(student, id);
        HttpResponse<String> retried = request(student, id);

        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(retried.body(), "$.level")).isEqualTo(2);
        assertThat(sentRequests()).extracting(FeedbackRequest::hintLevel).containsExactly(1, 2, 2);
        assertThat(storedLevels(id)).containsExactly(1, 2);
    }

    @Test
    void aRefusedTokenIsLoggedAsAConfigurationErrorAndNotRetried(CapturedOutput output) throws Exception {
        long id = graded(student);
        when(ai.feedback(any())).thenThrow(new AiServiceException("The AI service answered HTTP 401", 401));

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).isEqualTo(503);
        verify(ai, times(1)).feedback(any());
        assertThat(output.getOut())
                .contains("ERROR")
                .contains("The AI service refused the backend's token (HTTP 401)")
                .contains("AI_SERVICE_TOKEN");
    }

    @Test
    void anOutageIsLoggedAsAWarningWithItsReason(CapturedOutput output) throws Exception {
        long id = graded(student);
        when(ai.feedback(any()))
                .thenThrow(new AiServiceException(
                        "The AI service did not answer in time", new ResourceAccessException("timed out")));

        request(student, id);

        assertThat(output.getOut())
                .contains("WARN")
                .contains("No hint for submission " + id + " at level 1: The AI service did not answer in time")
                .doesNotContain("refused the backend's token")
                .doesNotContain("print(sum(map(int, input().split())))");
    }

    // ---- helpers ----

    /** Everything stored about the submission: its row, grade, criterion scores, results and hints. */
    private String snapshot(long submissionId) {
        return String.join(
                "\n",
                jdbc.queryForList("select * from submissions where id = ?", submissionId)
                        .toString(),
                jdbc.queryForList("select * from grades where submission_id = ?", submissionId)
                        .toString(),
                jdbc.queryForList(
                                "select * from criterion_scores where submission_id = ? order by criterion_id",
                                submissionId)
                        .toString(),
                jdbc.queryForList("select * from test_results where submission_id = ? order by id", submissionId)
                        .toString(),
                jdbc.queryForList("select * from ai_feedback where submission_id = ?", submissionId)
                        .toString());
    }

    private void answer(String hint) {
        when(ai.feedback(any())).thenReturn(new FeedbackResponse(hint, false));
    }

    private List<FeedbackRequest> sentRequests() {
        ArgumentCaptor<FeedbackRequest> captor = ArgumentCaptor.forClass(FeedbackRequest.class);
        verify(ai, atLeast(0)).feedback(captor.capture());
        return captor.getAllValues();
    }

    private HttpResponse<String> request(User as, long submissionId) throws Exception {
        return post("/submissions/" + submissionId + "/hints", "", tokenFor(as));
    }

    private List<Integer> storedLevels(long submissionId) {
        return jdbc.queryForList(
                "select hint_level from ai_feedback where submission_id = ? order by hint_level",
                Integer.class,
                submissionId);
    }

    private BigDecimal total(long submissionId) {
        return jdbc.queryForObject(
                "select total_score from grades where submission_id = ?", BigDecimal.class, submissionId);
    }

    private BigDecimal maxScore(long submissionId) {
        return jdbc.queryForObject(
                "select max_score from grades where submission_id = ?", BigDecimal.class, submissionId);
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private long submission(User by, String status) {
        return submission(by, status, 1);
    }

    private long submission(User by, String status, int attempt) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(sum(map(int, input().split())))', ?, ?) returning id",
                Long.class,
                assignment.getId(),
                by.getId(),
                attempt,
                status);
    }

    private long graded(User by) {
        return graded(by, 1);
    }

    private long gradedAgain(User by) {
        return graded(by, 2);
    }

    /** Graded as the worker grades it: p1 passed, h1 failed with output and stderr of its own. */
    private long graded(User by, int attempt) {
        return graded(by, attempt, TestOutcome.PASSED, TestOutcome.FAILED);
    }

    /** Graded as the worker grades it, with these outcomes for p1 and h1. */
    private long graded(User by, int attempt, TestOutcome publicOutcome, TestOutcome hiddenOutcome) {
        long id = submission(by, "RUNNING", attempt);
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), publicOutcome, "3\n", "", 12),
                        new TestResult(id, h1.getId(), hiddenOutcome, SECRETS[3], SECRETS[4], 13)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }
}
