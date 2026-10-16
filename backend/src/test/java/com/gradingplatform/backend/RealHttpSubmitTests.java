package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.EnrollmentId;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 5.1a: `POST /assignments/{id}/submissions` with the code as text, over real HTTP. */
class RealHttpSubmitTests extends RealHttpTestBase {

    @Autowired
    DataSource dataSource;

    private User owner;
    private User admin;
    private User student;
    private User classmate;
    private User outsider;
    private Course course;
    private Assignment assignment;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
        assignment = publishedAssignment("PYTHON");
    }

    private Assignment publishedAssignment(String language) {
        Assignment saved = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "d",
                language,
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", saved.getId());
        return saved;
    }

    private static String json(String sourceCode) {
        return "{\"sourceCode\":" + quoted(sourceCode) + "}";
    }

    /** A JSON string literal (only the escapes these tests need; other characters go as UTF-8). */
    private static String quoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private HttpResponse<String> submit(User as, long assignmentId, String body) throws Exception {
        return post("/assignments/" + assignmentId + "/submissions", body, as == null ? null : tokenFor(as));
    }

    private HttpResponse<String> submit(User as, String sourceCode) throws Exception {
        return submit(as, assignment.getId(), json(sourceCode));
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private static int attemptNo(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return JsonPath.read(response.body(), "$.attemptNo");
    }

    private Submission stored(HttpResponse<String> response) {
        long id = ((Number) JsonPath.read(response.body(), "$.id")).longValue();
        return submissions.findById(id).orElseThrow();
    }

    private <T> CompletableFuture<T> async(java.util.concurrent.Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // --- accepted ----------------------------------------------------------------------------

    @Test
    void anEnrolledStudentSubmitsAndGets202WithAQueuedSubmission() throws Exception {
        String code = "n = int(input())\r\n\tprint(n * 2)  \n# éè 中 \"quoted\" \\ end\n";

        HttpResponse<String> response = submit(student, code);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("id", "attemptNo", "status", "submittedAt");
        assertThat(body).containsEntry("attemptNo", 1).containsEntry("status", "QUEUED");

        Submission submission = stored(response);
        assertThat(submission.getAssignmentId()).isEqualTo(assignment.getId());
        assertThat(submission.getStudentId()).isEqualTo(student.getId());
        assertThat(submission.getLanguage()).isEqualTo("PYTHON");
        assertThat(submission.getSourceCode()).as("stored byte for byte").isEqualTo(code);
        // The worker pool (5.2a) may already have taken it; nothing finishes it yet.
        assertThat(submission.getStatus()).isIn(SubmissionStatus.QUEUED, SubmissionStatus.RUNNING);
        assertThat(submission.getFinishedAt()).isNull();
        assertThat(Instant.parse(JsonPath.read(response.body(), "$.submittedAt")))
                .isEqualTo(submission.getSubmittedAt());
    }

    @Test
    void eachSubmissionIsTheStudentsNextAttemptAtThatAssignment() throws Exception {
        Assignment other = publishedAssignment("JAVA");

        assertThat(attemptNo(submit(student, "print(1)"))).isEqualTo(1);
        assertThat(attemptNo(submit(student, "print(2)"))).isEqualTo(2);
        assertThat(attemptNo(submit(classmate, "print(3)")))
                .as("counted per student")
                .isEqualTo(1);
        assertThat(attemptNo(submit(student, other.getId(), json("class Main {}"))))
                .as("counted per assignment")
                .isEqualTo(1);
        assertThat(attemptNo(submit(student, "print(4)"))).isEqualTo(3);

        assertThat(submissions.count()).isEqualTo(5);
    }

    @Test
    void theLanguageIsTheAssignmentsAndOneInTheBodyIsIgnored() throws Exception {
        HttpResponse<String> response =
                submit(student, assignment.getId(), "{\"sourceCode\":\"print(1)\",\"language\":\"JAVA\"}");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(stored(response).getLanguage()).isEqualTo("PYTHON");
    }

    @Test
    void afterTheFirstSubmissionTheTeacherCanNoLongerChangeTheLanguage() throws Exception {
        assertThat(submit(student, "print(1)").statusCode()).isEqualTo(202);

        HttpResponse<String> change = send(
                "PUT",
                "/assignments/" + assignment.getId(),
                tokenFor(owner),
                "{\"title\":\"Sorting\",\"description\":\"d\",\"language\":\"JAVA\",\"deadline\":\""
                        + assignment.getDeadline() + "\",\"timeLimitMs\":2000,\"memoryLimitMb\":256}");

        assertThat(change.statusCode()).isEqualTo(409);
        assertThat(code(change)).isEqualTo("ASSIGNMENT_LANGUAGE_LOCKED");
    }

    // --- refused -----------------------------------------------------------------------------

    @Test
    void anAssignmentTheStudentMayNotSeeIsTheSame404AsOneThatDoesNotExist() throws Exception {
        HttpResponse<String> missing = submit(student, 987654321L, json("print(1)"));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("ASSIGNMENT_NOT_FOUND");

        Assignment draft = assignments.save(new Assignment(
                course.getId(), "Draft", "d", "PYTHON", assignment.getDeadline(), null, 2000, 256, null));
        Course otherCourse = courses.save(new Course("Other", null, owner.getId(), "WXYZ2345"));
        Assignment elsewhere = assignments.save(new Assignment(
                otherCourse.getId(), "Elsewhere", "d", "PYTHON", assignment.getDeadline(), null, 2000, 256, null));
        jdbc.update("update assignments set published = true where id = ?", elsewhere.getId());

        List<HttpResponse<String>> refused = List.of(
                submit(outsider, assignment.getId(), json("print(1)")),
                submit(student, draft.getId(), json("print(1)")),
                submit(student, elsewhere.getId(), json("print(1)")));

        for (HttpResponse<String> response : refused) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo(missing.body());
        }
        assertThat(submissions.count()).isZero();
    }

    @Test
    void aStudentWhoLeftTheCourseCanNoLongerSubmit() throws Exception {
        assertThat(submit(student, "print(1)").statusCode()).isEqualTo(202);
        enrollments.deleteById(new EnrollmentId(course.getId(), student.getId()));

        HttpResponse<String> response = submit(student, "print(2)");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("ASSIGNMENT_NOT_FOUND");
        assertThat(submissions.count()).as("the earlier attempt stays").isEqualTo(1);
    }

    @Test
    void teachersAndAdminsAreRefusedByRoleAndAnonymousIs401() throws Exception {
        for (User user : List.of(owner, admin)) {
            HttpResponse<String> response = submit(user, "print(1)");
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(code(response)).isEqualTo("ACCESS_DENIED");
        }
        assertThat(submit(null, "print(1)").statusCode()).isEqualTo(401);
        assertThat(submissions.count()).isZero();
    }

    @Test
    void codeThatIsMissingOrBlankIsRefusedAsInvalid() throws Exception {
        for (String body : List.of("{}", "{\"sourceCode\":null}", "{\"sourceCode\":\"\"}", json(" \r\n\t "))) {
            HttpResponse<String> response = submit(student, assignment.getId(), body);

            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
            assertThat((List<String>) JsonPath.read(response.body(), "$.fieldErrors[*].field"))
                    .containsExactly("sourceCode");
        }
        assertThat(submit(student, assignment.getId(), "not json").statusCode()).isEqualTo(400);
        assertThat(submissions.count()).isZero();
    }

    // --- races with the teacher --------------------------------------------------------------

    @Test
    void aSubmissionWaitsForALanguageChangeInFlightAndStoresTheNewLanguage() throws Exception {
        // The teacher's change holds the assignment's row lock (as every assignment write does) and has
        // switched the language, uncommitted. The submission must not store the old one.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from assignments where id = " + assignment.getId() + " for update");
                statement.executeUpdate("update assignments set language = 'JAVA' where id = " + assignment.getId());

                CompletableFuture<HttpResponse<String>> submitting = async(() -> submit(student, "class Main {}"));
                Thread.sleep(500);
                assertThat(submitting)
                        .as("the submission must wait for the change, not read the old language")
                        .isNotDone();

                connection.commit();
                HttpResponse<String> response = submitting.get(10, TimeUnit.SECONDS);

                assertThat(response.statusCode()).isEqualTo(202);
                assertThat(stored(response).getLanguage()).isEqualTo("JAVA");
            }
        }
    }

    @Test
    void aSubmissionWaitsForAnUnpublishInFlightAndIsThenRefused() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from assignments where id = " + assignment.getId() + " for update");
                statement.executeUpdate("update assignments set published = false where id = " + assignment.getId());

                CompletableFuture<HttpResponse<String>> submitting = async(() -> submit(student, "print(1)"));
                Thread.sleep(500);
                assertThat(submitting).isNotDone();

                connection.commit();
                HttpResponse<String> response = submitting.get(10, TimeUnit.SECONDS);

                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(code(response)).isEqualTo("ASSIGNMENT_NOT_FOUND");
            }
        }
        assertThat(submissions.count()).isZero();
    }

    @Test
    void submissionsOfDifferentStudentsDoNotWaitForEachOther() throws Exception {
        // A submission in flight (its lock held, uncommitted) does not hold up a classmate's.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from assignments where id = " + assignment.getId() + " for key share");

                HttpResponse<String> response =
                        async(() -> submit(classmate, "print(1)")).get(10, TimeUnit.SECONDS);

                assertThat(response.statusCode()).isEqualTo(202);
                connection.rollback();
            }
        }
    }
}
