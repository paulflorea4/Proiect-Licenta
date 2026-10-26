package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.gradingplatform.backend.ai.AiServiceClient;
import com.gradingplatform.backend.entity.AiFeedback;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.AiFeedbackRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 7.2b: `GET /submissions/{submissionId}/hints`, with the access rule and role-picked shape of the
 * submission itself (5.5a/5.5b). The AI client is the same mock as in {@link RealHttpHintRequestTests}
 * (one shared test context), here to show that reading hints never calls it.
 */
class RealHttpHintListTests extends RealHttpTestBase {

    @MockitoBean
    AiServiceClient ai;

    @Autowired
    private AiFeedbackRepository hints;

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private User classmate;
    private long submission;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "LIST2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
        Assignment assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        submission = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', 1, 'GRADED') returning id",
                Long.class,
                assignment.getId(),
                student.getId());
        reset(ai);
    }

    // ---- the student ----

    @Test
    void theStudentSeesTheirHintsLevelOneFirstWithLevelTextAndTimeOnly() throws Exception {
        hints.save(new AiFeedback(submission, 2, "Look at the operator in the print."));
        hints.save(new AiFeedback(submission, 1, "Look at the last line."));

        HttpResponse<String> response = list(student, submission);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$")).containsOnlyKeys("submissionId", "hints");
        assertThat(JsonPath.<Integer>read(response.body(), "$.submissionId")).isEqualTo((int) submission);
        assertThat(JsonPath.<List<Integer>>read(response.body(), "$.hints[*].level"))
                .containsExactly(1, 2);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.hints[*].content"))
                .containsExactly("Look at the last line.", "Look at the operator in the print.");
        for (Map<String, Object> hint : JsonPath.<List<Map<String, Object>>>read(response.body(), "$.hints")) {
            assertThat(hint).containsOnlyKeys("level", "content", "createdAt");
            assertThat(hint.get("createdAt")).isNotNull();
        }
    }

    @Test
    void theStudentDoesNotSeeTheirOwnReportOrTheHintsId() throws Exception {
        // The teacher reads the report (7.4b); the student's shape has no field for it.
        hints.save(new AiFeedback(submission, 1, "Look at the last line."));
        jdbc.update(
                "update ai_feedback set reported = true, report_reason = 'REPORT-MARKER', reported_at = now()"
                        + " where submission_id = ?",
                submission);

        String body = list(student, submission).body();

        assertThat(body)
                .doesNotContain("REPORT-MARKER")
                .doesNotContain("reported")
                .doesNotContain("\"id\"");
    }

    @Test
    void noHintsIsAnEmptyList() throws Exception {
        HttpResponse<String> response = list(student, submission);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.hints")).isEmpty();
    }

    @Test
    void anotherStudentIsForbiddenAndAMissingSubmissionIsNotFound() throws Exception {
        hints.save(new AiFeedback(submission, 1, "SECRET-HINT-TEXT"));

        HttpResponse<String> classmateAsks = list(classmate, submission);
        HttpResponse<String> missing = list(student, 987654321L);

        assertThat(classmateAsks.statusCode()).isEqualTo(403);
        assertThat(code(classmateAsks)).isEqualTo("ACCESS_DENIED");
        assertThat(classmateAsks.body()).doesNotContain("SECRET-HINT-TEXT");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("SUBMISSION_NOT_FOUND");
    }

    // ---- the people who run the course ----

    @Test
    void theOwnerAndAnAdminSeeEachHintWithItsIdAndTheReport() throws Exception {
        AiFeedback stored = hints.save(new AiFeedback(submission, 1, "Look at the last line."));
        jdbc.update(
                "update ai_feedback set reported = true, report_reason = 'Not helpful', reported_at = now()"
                        + " where id = ?",
                stored.getId());

        for (User reader : List.of(owner, admin)) {
            HttpResponse<String> response = list(reader, submission);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            Map<String, Object> hint = JsonPath.read(response.body(), "$.hints[0]");
            assertThat(hint)
                    .containsOnlyKeys("id", "level", "content", "createdAt", "reported", "reportReason", "reportedAt")
                    .containsEntry("id", stored.getId().intValue())
                    .containsEntry("level", 1)
                    .containsEntry("reported", true)
                    .containsEntry("reportReason", "Not helpful");
            assertThat(hint.get("reportedAt")).isNotNull();
        }
    }

    @Test
    void anUnreportedHintShowsReportedFalseForTheTeacher() throws Exception {
        hints.save(new AiFeedback(submission, 1, "Look at the last line."));

        Map<String, Object> hint = JsonPath.read(list(owner, submission).body(), "$.hints[0]");

        assertThat(hint).containsEntry("reported", false).containsEntry("reportReason", null);
    }

    @Test
    void anotherTeacherGetsTheNotFoundOfAMissingSubmission() throws Exception {
        hints.save(new AiFeedback(submission, 1, "SECRET-HINT-TEXT"));

        HttpResponse<String> other = list(otherTeacher, submission);
        HttpResponse<String> missing = list(otherTeacher, 987654321L);

        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(other.body()).isEqualTo(missing.body()).doesNotContain("SECRET-HINT-TEXT");
    }

    // ---- reading never generates ----

    @Test
    void readingHintsNeverCallsTheAiServiceNorStoresAnything() throws Exception {
        for (User reader : List.of(student, classmate, owner, otherTeacher, admin)) {
            list(reader, submission);
        }

        verify(ai, never()).feedback(any());
        assertThat(hints.count()).isZero();
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        assertThat(get("/submissions/" + submission + "/hints", null).statusCode())
                .isEqualTo(401);
    }

    // ---- helpers ----

    private HttpResponse<String> list(User as, long submissionId) throws Exception {
        return get("/submissions/" + submissionId + "/hints", tokenFor(as));
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }
}
