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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 7.2a: `POST /submissions/{submissionId}/hints`. The AI service is replaced by a mock, so these
 * tests see exactly what the backend sends it and decide what it answers.
 */
class RealHttpHintRequestTests extends RealHttpTestBase {

    private static final String[] SECRETS = {
        "SECRET-H-NAME", "SECRET-H-INPUT", "SECRET-H-EXPECTED", "SECRET-H-ACTUAL", "SECRET-H-STDERR"
    };
    private static final String DESCRIPTION = "Read two integers and print their sum.";

    @MockitoBean
    AiServiceClient ai;

    @Autowired
    private SubmissionResultWriter writer;

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
    void aHintNeverChangesTheGrade() throws Exception {
        long id = graded(student);
        BigDecimal before = total(id);
        answer("Look.");

        request(student, id);
        request(student, id);

        assertThat(total(id)).isEqualByComparingTo(before);
    }

    @Test
    void aFailedCallStoresNothing() throws Exception {
        // 7.3a turns this into a clear 503; here: no hint row, the level still unused.
        long id = graded(student);
        when(ai.feedback(any())).thenThrow(new AiServiceException("The AI service answered HTTP 503", 503));

        HttpResponse<String> response = request(student, id);

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(storedLevels(id)).isEmpty();
        verify(ai, times(1)).feedback(any());
    }

    // ---- helpers ----

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
        long id = submission(by, "RUNNING", attempt);
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.PASSED, "3\n", "", 12),
                        new TestResult(id, h1.getId(), TestOutcome.FAILED, SECRETS[3], SECRETS[4], 13)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }
}
