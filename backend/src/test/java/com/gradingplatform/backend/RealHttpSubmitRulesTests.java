package com.gradingplatform.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.service.SubmissionService;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.1c: the rules a submission must meet (deadline, attempt limit, size, storable text, language) and
 * that one student's submissions are made one at a time, over real HTTP. 5.1a and 5.1b hold the
 * shapes of the two request kinds.
 */
class RealHttpSubmitRulesTests extends RealHttpTestBase {

    private static final int MAX = SubmissionService.SOURCE_MAX_BYTES;
    /** Enough threads that a burst really is simultaneous, whatever the machine's core count. */
    private static final ExecutorService CALLERS = Executors.newCachedThreadPool();

    private static final Instant DEADLINE = Instant.parse("2031-06-30T21:59:59.123456Z");

    @Autowired
    DataSource dataSource;

    private User owner;
    private User student;
    private User classmate;
    private User outsider;
    private Course course;
    private Assignment assignment;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
        assignment = published(DEADLINE, null);
    }

    private Assignment published(Instant deadline, Integer maxAttempts) {
        Assignment saved = assignments.save(
                new Assignment(course.getId(), "Sorting", "d", "PYTHON", deadline, maxAttempts, 2000, 256, null));
        jdbc.update("update assignments set published = true where id = ?", saved.getId());
        return saved;
    }

    private HttpResponse<String> submit(User as, Assignment to, String json) throws Exception {
        return post("/assignments/" + to.getId() + "/submissions", json, tokenFor(as));
    }

    private HttpResponse<String> submit(User as, Assignment to) throws Exception {
        return submit(as, to, "{\"sourceCode\":\"print(1)\"}");
    }

    private HttpResponse<String> upload(User as, Assignment to, String fileName, String content) throws Exception {
        String boundary = "test-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/assignments/" + to.getId() + "/submissions"))
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + tokenFor(as))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    }

    private Submission stored(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        long id = ((Number) JsonPath.read(response.body(), "$.id")).longValue();
        return submissions.findById(id).orElseThrow();
    }

    private static <T> CompletableFuture<T> async(Callable<T> call) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return call.call();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                CALLERS);
    }

    // --- deadline ----------------------------------------------------------------------------

    @Test
    void theStoredTimeIsTheArrivalInstantTheDeadlineWasJudgedAgainst() throws Exception {
        Instant arrival = DEADLINE.minus(3, ChronoUnit.HOURS);
        clock.set(arrival);

        HttpResponse<String> response = submit(student, assignment);

        assertThat(stored(response).getSubmittedAt()).isEqualTo(arrival);
        assertThat(Instant.parse(JsonPath.read(response.body(), "$.submittedAt")))
                .isEqualTo(arrival);
    }

    @Test
    void aSubmissionAtExactlyTheDeadlineIsAcceptedAndOneMicrosecondLaterIsRefused() throws Exception {
        clock.set(DEADLINE);
        assertThat(stored(submit(student, assignment)).getSubmittedAt()).isEqualTo(DEADLINE);
        assertThat(stored(upload(student, assignment, "main.py", "print(2)")).getSubmittedAt())
                .isEqualTo(DEADLINE);

        clock.set(DEADLINE.plus(1, ChronoUnit.MICROS));
        assertRefused(submit(student, assignment), 409, "SUBMISSION_DEADLINE_PASSED");
        assertRefused(upload(student, assignment, "main.py", "print(3)"), 409, "SUBMISSION_DEADLINE_PASSED");

        clock.set(DEADLINE.plus(30, ChronoUnit.DAYS));
        assertRefused(submit(classmate, assignment), 409, "SUBMISSION_DEADLINE_PASSED");
        assertThat(submissions.count()).as("only the two on time").isEqualTo(2);
    }

    @Test
    void aLateSubmissionToAnAssignmentTheStudentMayNotSeeIsStillTheSame404() throws Exception {
        clock.set(DEADLINE.plus(1, ChronoUnit.DAYS));
        HttpResponse<String> missing =
                post("/assignments/987654321/submissions", "{\"sourceCode\":\"print(1)\"}", tokenFor(student));
        assertRefused(missing, 404, "ASSIGNMENT_NOT_FOUND");
        Assignment draft = assignments.save(
                new Assignment(course.getId(), "Draft", "d", "PYTHON", DEADLINE, null, 2000, 256, null));

        for (HttpResponse<String> response : List.of(submit(outsider, assignment), submit(student, draft))) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo(missing.body());
        }
    }

    @Test
    void aDeadlineTheTeacherMovesLaterLetsStudentsSubmitAgain() throws Exception {
        clock.set(DEADLINE.plus(1, ChronoUnit.HOURS));
        assertRefused(submit(student, assignment), 409, "SUBMISSION_DEADLINE_PASSED");

        jdbc.update(
                "update assignments set deadline = ? where id = ?",
                java.sql.Timestamp.from(DEADLINE.plus(2, ChronoUnit.HOURS)),
                assignment.getId());

        assertThat(stored(submit(student, assignment)).getAttemptNo()).isEqualTo(1);
    }

    @Test
    void aSubmissionWaitsForADeadlineChangeInFlightAndIsJudgedByTheNewDeadline() throws Exception {
        clock.set(DEADLINE.plus(1, ChronoUnit.HOURS));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from assignments where id = " + assignment.getId() + " for update");
                statement.executeUpdate("update assignments set deadline = deadline + interval '1 day' where id = "
                        + assignment.getId());

                CompletableFuture<HttpResponse<String>> submitting = async(() -> submit(student, assignment));
                Thread.sleep(500);
                assertThat(submitting).as("must wait for the teacher's change").isNotDone();

                connection.commit();
                assertThat(stored(submitting.get(10, TimeUnit.SECONDS)).getAttemptNo())
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void theDeadlineIsReportedBeforeTheAttemptLimitAndTheFileExtension() throws Exception {
        Assignment once = published(DEADLINE, 1);
        assertThat(stored(submit(student, once)).getAttemptNo()).isEqualTo(1);
        clock.set(DEADLINE.plus(1, ChronoUnit.SECONDS));

        assertRefused(submit(student, once), 409, "SUBMISSION_DEADLINE_PASSED");
        assertRefused(upload(student, assignment, "Main.java", "class Main {}"), 409, "SUBMISSION_DEADLINE_PASSED");
    }

    // --- attempt limit -----------------------------------------------------------------------

    @Test
    void theAttemptLimitCountsEachStudentsAttemptsAtThatAssignment() throws Exception {
        Assignment twice = published(DEADLINE, 2);

        assertThat(stored(submit(student, twice)).getAttemptNo()).isEqualTo(1);
        assertThat(stored(upload(student, twice, "main.py", "print(2)")).getAttemptNo())
                .as("an upload is an attempt too")
                .isEqualTo(2);
        assertRefused(submit(student, twice), 409, "ATTEMPT_LIMIT_REACHED");
        assertRefused(upload(student, twice, "main.py", "print(3)"), 409, "ATTEMPT_LIMIT_REACHED");

        assertThat(stored(submit(classmate, twice)).getAttemptNo())
                .as("per student")
                .isEqualTo(1);
        assertThat(stored(submit(student, assignment)).getAttemptNo())
                .as("per assignment")
                .isEqualTo(1);
        assertThat(submissions.count()).isEqualTo(4);
    }

    @Test
    void withoutALimitAttemptsAreUnlimited() throws Exception {
        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThat(stored(submit(student, assignment)).getAttemptNo()).isEqualTo(attempt);
        }
    }

    @Test
    void aLimitTheTeacherRaisesGivesTheStudentMoreAttempts() throws Exception {
        Assignment once = published(DEADLINE, 1);
        assertThat(stored(submit(student, once)).getAttemptNo()).isEqualTo(1);
        assertRefused(submit(student, once), 409, "ATTEMPT_LIMIT_REACHED");

        jdbc.update("update assignments set max_attempts = 2 where id = ?", once.getId());

        assertThat(stored(submit(student, once)).getAttemptNo()).isEqualTo(2);
        assertRefused(submit(student, once), 409, "ATTEMPT_LIMIT_REACHED");
    }

    /** Sets a submission's status as the worker (5.2b) will, by SQL: this phase has no worker yet. */
    private void endAs(Submission submission, String status) {
        jdbc.update("update submissions set status = ? where id = ?", status, submission.getId());
    }

    @Test
    void anAttemptThatEndedInErrorIsGivenBack() throws Exception {
        Assignment twice = published(DEADLINE, 2);
        Submission first = stored(submit(student, twice));
        stored(submit(student, twice));
        assertRefused(submit(student, twice), 409, "ATTEMPT_LIMIT_REACHED");

        endAs(first, "ERROR");

        assertThat(stored(submit(student, twice)).getAttemptNo())
                .as("the attempt comes back, the number still counts every submission")
                .isEqualTo(3);
        assertRefused(submit(student, twice), 409, "ATTEMPT_LIMIT_REACHED");
        assertThat(submissions.count()).as("the failed submission is kept").isEqualTo(3);
    }

    @Test
    void attemptsInEveryOtherStatusCount() throws Exception {
        Assignment thrice = published(DEADLINE, 3);
        endAs(stored(submit(student, thrice)), "GRADED");
        endAs(stored(submit(student, thrice)), "RUNNING");
        stored(submit(student, thrice)); // QUEUED

        assertRefused(submit(student, thrice), 409, "ATTEMPT_LIMIT_REACHED");
    }

    @Test
    void anotherStudentsErrorDoesNotGiveThisStudentAnAttempt() throws Exception {
        Assignment once = published(DEADLINE, 1);
        stored(submit(student, once));
        endAs(stored(submit(classmate, once)), "ERROR");

        assertRefused(submit(student, once), 409, "ATTEMPT_LIMIT_REACHED");
    }

    @Test
    void aBurstAfterAnErrorAcceptsExactlyTheAttemptsLeft() throws Exception {
        Assignment twice = published(DEADLINE, 2);
        endAs(stored(submit(student, twice)), "ERROR");

        List<CompletableFuture<HttpResponse<String>>> burst = IntStream.range(0, 6)
                .mapToObj(i -> async(() -> submit(student, twice)))
                .toList();

        List<Integer> accepted = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> call : burst) {
            HttpResponse<String> response = call.get(30, TimeUnit.SECONDS);
            if (response.statusCode() == 202) {
                accepted.add(stored(response).getAttemptNo());
            } else {
                assertRefused(response, 409, "ATTEMPT_LIMIT_REACHED");
            }
        }
        assertThat(accepted).containsExactlyInAnyOrder(2, 3);
    }

    // --- one student's submissions are made one at a time -----------------------------------

    @Test
    void aStudentsSecondSubmissionWaitsForTheFirstAndGetsTheNextNumber() throws Exception {
        // The test connection plays a submission in flight: it holds the enrollment row's lock.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select 1 from enrollments where course_id = " + course.getId() + " and student_id = "
                        + student.getId() + " for update");

                CompletableFuture<HttpResponse<String>> first = async(() -> submit(student, assignment));
                CompletableFuture<HttpResponse<String>> second = async(() -> submit(student, assignment));
                HttpResponse<String> classmates =
                        async(() -> submit(classmate, assignment)).get(10, TimeUnit.SECONDS);
                Thread.sleep(500);
                assertThat(first).isNotDone();
                assertThat(second).isNotDone();
                assertThat(stored(classmates).getAttemptNo())
                        .as("another student does not wait")
                        .isEqualTo(1);

                connection.rollback();
                List<Integer> numbers = List.of(
                        stored(first.get(10, TimeUnit.SECONDS)).getAttemptNo(),
                        stored(second.get(10, TimeUnit.SECONDS)).getAttemptNo());
                assertThat(numbers).containsExactlyInAnyOrder(1, 2);
            }
        }
    }

    @Test
    void aBurstOfSimultaneousSubmissionsGetsDistinctConsecutiveNumbers() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> burst = IntStream.range(0, 8)
                .mapToObj(i -> async(() -> submit(student, assignment)))
                .toList();

        List<Integer> numbers = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> call : burst) {
            numbers.add(stored(call.get(30, TimeUnit.SECONDS)).getAttemptNo());
        }
        assertThat(numbers).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void aBurstAtTheAttemptLimitAcceptsExactlyTheAttemptsLeft() throws Exception {
        Assignment thrice = published(DEADLINE, 3);
        List<CompletableFuture<HttpResponse<String>>> burst = IntStream.range(0, 8)
                .mapToObj(i -> async(() -> submit(student, thrice)))
                .toList();

        List<Integer> accepted = new ArrayList<>();
        int refused = 0;
        for (CompletableFuture<HttpResponse<String>> call : burst) {
            HttpResponse<String> response = call.get(30, TimeUnit.SECONDS);
            if (response.statusCode() == 202) {
                accepted.add(stored(response).getAttemptNo());
            } else {
                assertRefused(response, 409, "ATTEMPT_LIMIT_REACHED");
                refused++;
            }
        }
        assertThat(accepted).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(refused).isEqualTo(5);
    }

    @Test
    void aSubmissionWaitsForTheStudentLeavingTheCourseAndIsThenRefused() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("delete from enrollments where course_id = " + course.getId()
                        + " and student_id = " + student.getId());

                CompletableFuture<HttpResponse<String>> submitting = async(() -> submit(student, assignment));
                Thread.sleep(500);
                assertThat(submitting).isNotDone();

                connection.commit();
                assertRefused(submitting.get(10, TimeUnit.SECONDS), 404, "ASSIGNMENT_NOT_FOUND");
            }
        }
        assertThat(submissions.count()).isZero();
    }

    // --- size and text -----------------------------------------------------------------------

    @Test
    void codeSentAsTextHasTheSameByteLimitAsAFile() throws Exception {
        String atLimit = "print(1)\n" + "#".repeat(MAX - 9);
        assertThat(stored(submit(student, assignment, "{\"sourceCode\":\"print(1)\\n" + "#".repeat(MAX - 9) + "\"}"))
                        .getSourceCode())
                .isEqualTo(atLimit);

        assertRefused(
                submit(student, assignment, "{\"sourceCode\":\"print(1)\\n" + "#".repeat(MAX - 8) + "\"}"),
                413,
                "SOURCE_TOO_LARGE");
        // Fewer characters than the limit, but two bytes each in UTF-8.
        assertRefused(
                submit(student, assignment, "{\"sourceCode\":\"#" + "é".repeat(MAX / 2) + "\"}"),
                413,
                "SOURCE_TOO_LARGE");
        assertThat(submissions.count()).isEqualTo(1);
    }

    @Test
    void textThatTheDatabaseCannotStoreAsSentIsRefused() throws Exception {
        List<String> escapes = List.of(
                "print(1)\\u0000", // NUL: Postgres TEXT rejects it
                "print(1) \\ud800", // a lone high surrogate
                "\\udc00 print(1)", // a lone low surrogate
                "\\ude00\\ud83d print(1)"); // a pair in the wrong order
        for (String escaped : escapes) {
            assertRefused(submit(student, assignment, "{\"sourceCode\":\"" + escaped + "\"}"), 400, "SOURCE_NOT_TEXT");
        }
        assertThat(submissions.count()).isZero();
    }

    @Test
    void charactersOutsideTheBasicPlaneAreStoredIntact() throws Exception {
        assertThat(stored(submit(student, assignment, "{\"sourceCode\":\"print('\\ud83d\\ude00 😀')\"}"))
                        .getSourceCode())
                .isEqualTo("print('😀 😀')");
    }

    // --- language ----------------------------------------------------------------------------

    @Test
    void anAssignmentWhoseLanguageIsNoLongerConfiguredRefusesTextToo() throws Exception {
        jdbc.update("update assignments set language = 'NOT_CONFIGURED' where id = ?", assignment.getId());

        assertRefused(submit(student, assignment), 400, "UNSUPPORTED_LANGUAGE");
        assertThat(submissions.count()).isZero();
    }
}
