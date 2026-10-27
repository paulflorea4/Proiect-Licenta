package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.ai.AiServiceClient;
import com.gradingplatform.backend.ai.FeedbackRequest;
import com.gradingplatform.backend.ai.FeedbackResponse;
import com.gradingplatform.backend.entity.AiFeedback;
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
import com.gradingplatform.backend.repository.AiFeedbackRepository;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionResultWriter;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 7.5b: the life of a submission's hints, request by request. Each request asks for the next level
 * (7.2a); a repeat that arrives while the first is still waiting for the AI service returns the row
 * the first stored, at every level; a stored row is never generated again; a fourth request is
 * refused without calling the AI service and changes nothing. The AI client is the same mock as in
 * {@link RealHttpHintRequestTests} (one shared test context).
 */
class RealHttpHintProgressionTests extends RealHttpTestBase {

    @MockitoBean
    AiServiceClient ai;

    @Autowired
    private SubmissionResultWriter writer;

    @Autowired
    private AiFeedbackRepository hints;

    private User student;
    private Assignment assignment;
    private TestCase p1;
    private TestCase h1;
    private long submission;

    @BeforeEach
    void world() {
        User owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "PROG2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                "Read two integers and print their sum.",
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
        h1 = testCases.save(
                new TestCase(assignment.getId(), tests.getId(), "hidden", "4 5", "9", TestVisibility.HIDDEN, 1, 2));
        submission = graded();
        reset(ai);
    }

    // ---- 1 -> 2 -> 3, then refused ----

    @Test
    void eachRequestStoresTheNextLevelAndEarlierRowsNeverChange() throws Exception {
        when(ai.feedback(any())).thenAnswer(call -> {
            int level = call.<FeedbackRequest>getArgument(0).hintLevel();
            return new FeedbackResponse("Hint at level " + level + ".", false);
        });
        List<Map<String, Object>> before = List.of();

        for (int level = 1; level <= 3; level++) {
            HttpResponse<String> response = hint();

            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(JsonPath.<Integer>read(response.body(), "$.level")).isEqualTo(level);
            assertThat(JsonPath.<String>read(response.body(), "$.content")).isEqualTo("Hint at level " + level + ".");
            List<Map<String, Object>> now = rows();
            assertThat(now).hasSize(level);
            assertThat(now.subList(0, level - 1))
                    .as("the rows of earlier levels are the same rows, with the same text and time")
                    .isEqualTo(before);
            assertThat(listedLevels()).containsExactly(levelsUpTo(level));
            before = now;
        }
        assertThat(sentLevels()).containsExactly(1, 2, 3);
    }

    @Test
    void aFourthRequestIsRefusedWithoutAskingAndChangesNothing() throws Exception {
        when(ai.feedback(any())).thenReturn(new FeedbackResponse("A hint.", false));
        for (int level = 1; level <= 3; level++) {
            assertThat(hint().statusCode()).isEqualTo(201);
        }
        List<Map<String, Object>> stored = rows();
        String grade = grade();
        reset(ai);

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> response = hint();
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("HINTS_USED_UP");
        }

        verify(ai, never()).feedback(any());
        assertThat(rows()).isEqualTo(stored);
        assertThat(grade()).isEqualTo(grade);
        HttpResponse<String> view = get("/submissions/" + submission, tokenFor(student));
        assertThat(JsonPath.<Integer>read(view.body(), "$.hintsUsed")).isEqualTo(3);
    }

    @Test
    void threeLevelsStoredByAnyRouteAreThreeLevelsUsed() throws Exception {
        hints.save(new AiFeedback(submission, 1, "One."));
        hints.save(new AiFeedback(submission, 2, "Two."));
        hints.save(new AiFeedback(submission, 3, "Three."));

        HttpResponse<String> response = hint();

        assertThat(response.statusCode()).isEqualTo(409);
        verify(ai, never()).feedback(any());
    }

    @Test
    void aFallbackOnTheWayDoesNotMoveTheProgression() throws Exception {
        when(ai.feedback(any()))
                .thenReturn(new FeedbackResponse("One.", false))
                .thenReturn(new FeedbackResponse("Trace a failing test by hand.", true))
                .thenReturn(new FeedbackResponse("Two.", false))
                .thenReturn(new FeedbackResponse("Three.", false));

        List<Integer> statuses = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (int request = 0; request < 4; request++) {
            HttpResponse<String> response = hint();
            statuses.add(response.statusCode());
            levels.add(JsonPath.read(response.body(), "$.level"));
        }
        HttpResponse<String> fifth = hint();

        assertThat(statuses).containsExactly(201, 200, 201, 201);
        assertThat(levels).containsExactly(1, 2, 2, 3);
        assertThat(fifth.statusCode()).isEqualTo(409);
        assertThat(sentLevels()).containsExactly(1, 2, 2, 3);
        assertThat(rows()).extracting(r -> r.get("content")).containsExactly("One.", "Two.", "Three.");
    }

    // ---- a repeat returns the row the first request stored ----

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void aRepeatWhileTheFirstStillWaitsReturnsTheSameRowAtEveryLevel(int level) throws Exception {
        for (int earlier = 1; earlier < level; earlier++) {
            hints.save(new AiFeedback(submission, earlier, "Earlier " + earlier + "."));
        }
        CountDownLatch bothAsked = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(ai.feedback(any())).thenAnswer(call -> {
            int n = calls.incrementAndGet();
            bothAsked.countDown();
            // Both requests have counted the same hints before either stores one.
            assertThat(bothAsked.await(10, TimeUnit.SECONDS)).isTrue();
            return new FeedbackResponse("Answer " + n + ".", false);
        });

        List<HttpResponse<String>> responses = twoAtOnce();

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200);
        Map<String, Object> stored = jdbc.queryForMap(
                "select content, created_at from ai_feedback where submission_id = ? and hint_level = ?",
                submission,
                level);
        for (HttpResponse<String> response : responses) {
            assertThat(JsonPath.<Integer>read(response.body(), "$.level")).isEqualTo(level);
            assertThat(JsonPath.<String>read(response.body(), "$.content")).isEqualTo(stored.get("content"));
            assertThat(JsonPath.<Boolean>read(response.body(), "$.fallback")).isFalse();
        }
        assertThat(responses.get(0).body()).isEqualTo(responses.get(1).body());
        assertThat(rows()).hasSize(level);
        assertThat(sentLevels()).containsExactly(level, level);
    }

    @Test
    void afterARepeatTheNextRequestAsksForTheNextLevel() throws Exception {
        CountDownLatch bothAsked = new CountDownLatch(2);
        when(ai.feedback(any())).thenAnswer(call -> {
            FeedbackRequest request = call.getArgument(0);
            if (request.hintLevel() == 1) {
                bothAsked.countDown();
                assertThat(bothAsked.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return new FeedbackResponse("Level " + request.hintLevel() + ".", false);
        });
        twoAtOnce();

        HttpResponse<String> next = hint();

        assertThat(next.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(next.body(), "$.level")).isEqualTo(2);
        assertThat(rows()).extracting(r -> r.get("hint_level")).containsExactly(1, 2);
    }

    // ---- helpers ----

    private HttpResponse<String> hint() throws Exception {
        return post("/submissions/" + submission + "/hints", "", tokenFor(student));
    }

    private List<HttpResponse<String>> twoAtOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(this::hint));
            }
            return List.of(
                    futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** The submission's stored hints as rows, level 1 first: id, level, text and time. */
    private List<Map<String, Object>> rows() {
        return jdbc.queryForList(
                "select id, hint_level, content, created_at from ai_feedback where submission_id = ? order by hint_level",
                submission);
    }

    private List<Integer> listedLevels() throws Exception {
        HttpResponse<String> list = get("/submissions/" + submission + "/hints", tokenFor(student));
        return JsonPath.read(list.body(), "$.hints[*].level");
    }

    private static Integer[] levelsUpTo(int level) {
        Integer[] levels = new Integer[level];
        for (int i = 0; i < level; i++) {
            levels[i] = i + 1;
        }
        return levels;
    }

    private List<Integer> sentLevels() {
        ArgumentCaptor<FeedbackRequest> captor = ArgumentCaptor.forClass(FeedbackRequest.class);
        verify(ai, atLeast(0)).feedback(captor.capture());
        return captor.getAllValues().stream().map(FeedbackRequest::hintLevel).toList();
    }

    private String grade() {
        return jdbc.queryForList("select * from grades where submission_id = ?", submission)
                .toString();
    }

    /** Graded as the worker grades it: p1 passed, h1 failed, so hints may be asked for (7.2c). */
    private long graded() {
        long id = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(sum(map(int, input().split())))', 1, 'RUNNING') returning id",
                Long.class,
                assignment.getId(),
                student.getId());
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.PASSED, "3\n", "", 12),
                        new TestResult(id, h1.getId(), TestOutcome.FAILED, "8\n", "", 13)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }
}
