package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.gradingplatform.backend.ai.AiServiceClient;
import com.gradingplatform.backend.dto.HintReportRequest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 7.4b: `POST /submissions/{submissionId}/hints/{level}/report`. The AI client is the same mock as
 * in {@link RealHttpHintRequestTests} (one shared test context), here to show a report never calls it.
 */
class RealHttpHintReportTests extends RealHttpTestBase {

    private static final Instant FIRST = Instant.parse("2026-10-20T10:00:00.123456Z");
    private static final Instant LATER = Instant.parse("2026-10-21T11:00:00Z");

    @MockitoBean
    AiServiceClient ai;

    @Autowired
    private SubmissionResultWriter writer;

    @Autowired
    private AiFeedbackRepository hints;

    @Autowired
    private MutableClock clock;

    private User owner;
    private User admin;
    private User student;
    private User classmate;
    private Assignment assignment;
    private TestCase p1;
    private TestCase h1;
    private long submission;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "REPT2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
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
        submission = graded(student, 1);
        hints.save(new AiFeedback(submission, 1, "Look at the last line."));
        hints.save(new AiFeedback(submission, 2, "The operator in the print is the problem."));
        clock.set(FIRST);
        reset(ai);
    }

    @AfterEach
    void systemClock() {
        clock.reset();
    }

    // ---- a report is stored ----

    @Test
    void aReportWithAReasonIsStoredAndAnswered() throws Exception {
        HttpResponse<String> response =
                report(student, submission, 1, "{\"reason\":\"It talks about a loop I do not have.\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$"))
                .containsOnlyKeys("submissionId", "level", "reported", "reportReason", "reportedAt")
                .containsEntry("level", 1)
                .containsEntry("reported", true)
                .containsEntry("reportReason", "It talks about a loop I do not have.");
        assertThat(Instant.parse(JsonPath.read(response.body(), "$.reportedAt")))
                .isEqualTo(FIRST);
        assertThat(stored(1))
                .containsEntry("reported", true)
                .containsEntry("report_reason", "It talks about a loop I do not have.");
        assertThat(stored(2)).containsEntry("reported", false).containsEntry("report_reason", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"reason\":null}", "{\"reason\":\"   \"}"})
    void theReasonIsOptional(String body) throws Exception {
        HttpResponse<String> response = report(student, submission, 2, body);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(response.body(), "$.reportReason")).isNull();
        assertThat(stored(2)).containsEntry("reported", true).containsEntry("report_reason", null);
        assertThat(stored(2).get("reported_at")).isNotNull();
    }

    @Test
    void theHintItselfIsNeverAlteredAndTheGradeNeverChanges() throws Exception {
        String content = (String) stored(1).get("content");
        Object created = stored(1).get("created_at");
        String grade = grade();

        report(student, submission, 1, "{\"reason\":\"Wrong.\"}");

        assertThat(stored(1)).containsEntry("content", content).containsEntry("created_at", created);
        assertThat(grade()).isEqualTo(grade);
        assertThat(jdbc.queryForObject("select count(*) from ai_feedback", Integer.class))
                .isEqualTo(2);
        verify(ai, never()).feedback(any());
    }

    // ---- idempotent: the first report is kept ----

    @Test
    void reportingAgainKeepsTheFirstReasonAndTime() throws Exception {
        HttpResponse<String> first = report(student, submission, 1, "{\"reason\":\"First.\"}");
        clock.set(LATER);

        HttpResponse<String> again = report(student, submission, 1, "{\"reason\":\"Second.\"}");

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(JsonPath.<String>read(again.body(), "$.reportReason")).isEqualTo("First.");
        assertThat(Instant.parse(JsonPath.read(again.body(), "$.reportedAt"))).isEqualTo(FIRST);
        assertThat(stored(1)).containsEntry("report_reason", "First.");
    }

    @Test
    void twoReportsAtOnceStoreOneAndBothAnswerIt() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String reason : List.of("A", "B")) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return report(student, submission, 1, "{\"reason\":\"" + reason + "\"}");
                }));
            }
            go.countDown();
            HttpResponse<String> a = futures.get(0).get(30, TimeUnit.SECONDS);
            HttpResponse<String> b = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(a.statusCode()).isEqualTo(200);
            assertThat(b.statusCode()).isEqualTo(200);
            assertThat(a.body()).isEqualTo(b.body());
            assertThat(stored(1).get("report_reason")).isEqualTo(JsonPath.read(a.body(), "$.reportReason"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- who sees the report ----

    @Test
    void theReportAppearsInTheTeachersSubmissionViewAndHintList() throws Exception {
        report(student, submission, 2, "{\"reason\":\"Unhelpful.\"}");

        for (User as : List.of(owner, admin)) {
            HttpResponse<String> view = get("/submissions/" + submission, tokenFor(as));
            assertThat(view.statusCode()).as(view.body()).isEqualTo(200);
            assertThat(JsonPath.<List<Integer>>read(view.body(), "$.hints[*].level"))
                    .containsExactly(1, 2);
            assertThat(JsonPath.<List<Boolean>>read(view.body(), "$.hints[*].reported"))
                    .containsExactly(false, true);
            assertThat(JsonPath.<String>read(view.body(), "$.hints[1].reportReason"))
                    .isEqualTo("Unhelpful.");
            assertThat(Instant.parse(JsonPath.read(view.body(), "$.hints[1].reportedAt")))
                    .isEqualTo(FIRST);

            HttpResponse<String> list = get("/submissions/" + submission + "/hints", tokenFor(as));
            assertThat(JsonPath.<Boolean>read(list.body(), "$.hints[1].reported"))
                    .isTrue();
        }
    }

    @Test
    void theStudentsOwnViewsCarryNoReportFields() throws Exception {
        report(student, submission, 1, "{\"reason\":\"SECRET-REASON\"}");

        HttpResponse<String> view = get("/submissions/" + submission, tokenFor(student));
        HttpResponse<String> list = get("/submissions/" + submission + "/hints", tokenFor(student));

        assertThat(JsonPath.<Map<String, Object>>read(view.body(), "$")).doesNotContainKey("hints");
        assertThat(view.body()).doesNotContain("SECRET-REASON").doesNotContain("reported");
        assertThat(list.body()).doesNotContain("SECRET-REASON").doesNotContain("reported");
    }

    @Test
    void anotherStudentGetsNeitherTheReportNorTheHints() throws Exception {
        report(student, submission, 1, "{\"reason\":\"SECRET-REASON\"}");

        for (String path : List.of("/submissions/" + submission, "/submissions/" + submission + "/hints")) {
            HttpResponse<String> response = get(path, tokenFor(classmate));
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).doesNotContain("SECRET-REASON");
        }
    }

    // ---- refusals: nothing is changed ----

    @Test
    void anotherStudentsHintCannotBeReported() throws Exception {
        HttpResponse<String> response = report(classmate, submission, 1, "{\"reason\":\"x\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("ACCESS_DENIED");
        assertThat(stored(1)).containsEntry("reported", false);
    }

    @Test
    void aMissingSubmissionIsNotFound() throws Exception {
        HttpResponse<String> response = report(student, 987654321L, 1, "");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("SUBMISSION_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 0, 4, -1})
    void aLevelWithoutAStoredHintIsNotFound(int level) throws Exception {
        HttpResponse<String> response = report(student, submission, level, "");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("HINT_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from ai_feedback where reported", Integer.class))
                .isZero();
    }

    @Test
    void aLevelThatIsNotANumberIsABadRequest() throws Exception {
        HttpResponse<String> response = post("/submissions/" + submission + "/hints/one/report", "", tokenFor(student));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void theCourseOwnerCannotReportAStudentsHint() throws Exception {
        HttpResponse<String> response = report(owner, submission, 1, "");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(stored(1)).containsEntry("reported", false);
    }

    @Test
    void aReasonUpToTheCapIsStoredAndOneCharacterMoreIsRefused() throws Exception {
        String atCap = "r".repeat(HintReportRequest.REASON_MAX);

        HttpResponse<String> tooLong = report(student, submission, 1, "{\"reason\":\"" + atCap + "r\"}");

        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(code(tooLong)).isEqualTo("VALIDATION_FAILED");
        assertThat(tooLong.body()).doesNotContain(atCap);
        assertThat(stored(1)).containsEntry("reported", false);

        HttpResponse<String> fits = report(student, submission, 1, "{\"reason\":\"" + atCap + "\"}");

        assertThat(fits.statusCode()).isEqualTo(200);
        assertThat(stored(1)).containsEntry("report_reason", atCap);
    }

    @Test
    void aReasonWithANulCharacterIsRefusedNotAServerError() throws Exception {
        HttpResponse<String> response = report(student, submission, 1, "{\"reason\":\"bad\\u0000reason\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(stored(1)).containsEntry("reported", false);
    }

    // ---- helpers ----

    private HttpResponse<String> report(User as, long submissionId, int level, String body) throws Exception {
        return post("/submissions/" + submissionId + "/hints/" + level + "/report", body, tokenFor(as));
    }

    private Map<String, Object> stored(int level) {
        return jdbc.queryForMap(
                "select content, created_at, reported, report_reason, reported_at from ai_feedback"
                        + " where submission_id = ? and hint_level = ?",
                submission,
                level);
    }

    private String grade() {
        return jdbc.queryForList("select * from grades where submission_id = ?", submission)
                .toString();
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    /** Graded as the worker grades it: p1 passed, h1 failed. */
    private long graded(User by, int attempt) {
        long id = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(sum(map(int, input().split())))', ?, 'RUNNING') returning id",
                Long.class,
                assignment.getId(),
                by.getId(),
                attempt);
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
