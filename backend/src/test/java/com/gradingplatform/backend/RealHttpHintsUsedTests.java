package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.ai.AiServiceClient;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 7.4a: `hintsUsed` on `GET /submissions/{submissionId}`, in the student's and the teacher's view,
 * counted from the submission's `ai_feedback` rows. The AI client is the same mock as in {@link
 * RealHttpHintRequestTests} (one shared test context).
 */
class RealHttpHintsUsedTests extends RealHttpTestBase {

    @MockitoBean
    AiServiceClient ai;

    @Autowired
    private SubmissionResultWriter writer;

    @Autowired
    private AiFeedbackRepository hints;

    private User owner;
    private User admin;
    private User student;
    private Assignment assignment;
    private TestCase p1;
    private TestCase h1;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "USED2345"));
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
        reset(ai);
    }

    @Test
    void theCountFollowsEachHintTheStudentReceivesInBothViews() throws Exception {
        long id = graded(1);
        String grade = gradeOf(view(student, id));
        when(ai.feedback(any())).thenReturn(new FeedbackResponse("A hint.", false));

        for (int used = 0; used <= 3; used++) {
            assertThat(hintsUsed(view(student, id)))
                    .as("student, after %d", used)
                    .isEqualTo(used);
            assertThat(hintsUsed(view(owner, id))).as("owner, after %d", used).isEqualTo(used);
            assertThat(hintsUsed(view(admin, id))).as("admin, after %d", used).isEqualTo(used);
            assertThat(gradeOf(view(student, id)))
                    .as("a hint never changes the grade")
                    .isEqualTo(grade);
            if (used < 3) {
                assertThat(hint(id).statusCode()).isEqualTo(201);
            }
        }
        assertThat(hint(id).statusCode())
                .as("a fourth is refused and changes nothing")
                .isEqualTo(409);
        assertThat(hintsUsed(view(student, id))).isEqualTo(3);
    }

    @Test
    void aFallbackIsNotCounted() throws Exception {
        long id = graded(1);
        when(ai.feedback(any())).thenReturn(new FeedbackResponse("Trace a failing test by hand.", true));

        assertThat(hint(id).statusCode()).isEqualTo(200);

        assertThat(hintsUsed(view(student, id))).isZero();
        assertThat(hintsUsed(view(owner, id))).isZero();
    }

    @Test
    void theCountIsDerivedFromTheStoredRows() throws Exception {
        long id = graded(1);
        hints.save(new AiFeedback(id, 1, "One."));
        hints.save(new AiFeedback(id, 2, "Two."));

        assertThat(hintsUsed(view(student, id))).isEqualTo(2);

        jdbc.update("delete from ai_feedback where submission_id = ? and hint_level = 2", id);

        assertThat(hintsUsed(view(student, id))).isEqualTo(1);
        assertThat(hintsUsed(view(owner, id))).isEqualTo(1);
    }

    @Test
    void eachSubmissionCountsOnlyItsOwnHints() throws Exception {
        long first = graded(1);
        long second = graded(2);
        hints.save(new AiFeedback(first, 1, "One."));
        hints.save(new AiFeedback(first, 2, "Two."));

        assertThat(hintsUsed(view(student, first))).isEqualTo(2);
        assertThat(hintsUsed(view(student, second))).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "ERROR"})
    void anUnfinishedSubmissionShowsZeroInBothViews(String status) throws Exception {
        long id = submission(status, 1);

        for (User as : List.of(student, owner)) {
            HttpResponse<String> response = view(as, id);
            assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$")).containsEntry("hintsUsed", 0);
        }
    }

    // ---- helpers ----

    private HttpResponse<String> view(User as, long submissionId) throws Exception {
        HttpResponse<String> response = get("/submissions/" + submissionId, tokenFor(as));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response;
    }

    private HttpResponse<String> hint(long submissionId) throws Exception {
        return post("/submissions/" + submissionId + "/hints", "", tokenFor(student));
    }

    private static int hintsUsed(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.hintsUsed");
    }

    private static String gradeOf(HttpResponse<String> response) {
        return JsonPath.parse(response.body()).read("$.grade").toString();
    }

    private long submission(String status, int attempt) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(sum(map(int, input().split())))', ?, ?) returning id",
                Long.class,
                assignment.getId(),
                student.getId(),
                attempt,
                status);
    }

    /** Graded as the worker grades it: p1 passed, h1 failed, so hints may be asked for (7.2c). */
    private long graded(int attempt) {
        long id = submission("RUNNING", attempt);
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
