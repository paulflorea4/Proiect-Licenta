package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 9.0a: `POST /submissions/{id}/rerun`, a teacher's re-run of a submission that ended in `ERROR`
 * (the human's choice), over real HTTP. Submissions are inserted by SQL in their final state. A
 * re-run one is graded by the context's real worker; the assignment has no test cases, so grading
 * runs no container and ends `GRADED` at once (5.3a), which shows the worker was given it.
 */
class RealHttpSubmissionRerunTests extends RealHttpTestBase {

    /** When the failed attempt arrived: kept by the re-run, whatever the deadline says now. */
    private static final Instant ARRIVED = Instant.parse("2026-10-01T09:59:30Z");

    private static final Instant OLD_START = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant OLD_FINISH = Instant.parse("2026-10-01T10:00:05Z");

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private Assignment assignment;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 100));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
    }

    /** The student's submission in its final {@code status}, with an old run and arrival; its id. */
    private long submission(int attemptNo, String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status,"
                        + " submitted_at, started_at, finished_at) values (?, ?, 'PYTHON', 'print(41 + 1)', ?, ?, ?,"
                        + " ?, ?) returning id",
                Long.class,
                assignment.getId(),
                student.getId(),
                attemptNo,
                status,
                Timestamp.from(ARRIVED),
                Timestamp.from(OLD_START),
                Timestamp.from(OLD_FINISH));
    }

    private HttpResponse<String> rerun(User as, long submissionId) throws Exception {
        return send("POST", "/submissions/" + submissionId + "/rerun", as == null ? null : tokenFor(as), null);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select * from submissions where id = ?", id);
    }

    private String status(long id) {
        return (String) row(id).get("status");
    }

    /** Waits for the worker (5.2a) to finish the re-run submission. */
    private void awaitStatus(long id, String expected) throws InterruptedException {
        Instant until = Instant.now().plus(Duration.ofSeconds(30));
        while (!expected.equals(status(id)) && Instant.now().isBefore(until)) {
            Thread.sleep(50);
        }
        assertThat(status(id)).isEqualTo(expected);
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    // --- re-run ------------------------------------------------------------------------------

    @Test
    void theOwnerReRunsAnErrorSubmissionWhichIsThenGradedWithItsOwnCodeAttemptAndArrival() throws Exception {
        long id = submission(1, "ERROR");
        Instant before = Instant.now();

        HttpResponse<String> response = rerun(owner, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("id", "attemptNo", "status", "submittedAt");
        assertThat(JsonPath.<Number>read(response.body(), "$.id").longValue()).isEqualTo(id);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("QUEUED");
        assertThat(Instant.parse(JsonPath.read(response.body(), "$.submittedAt")))
                .isEqualTo(ARRIVED);
        awaitStatus(id, "GRADED");
        Map<String, Object> row = row(id);
        assertThat(row.get("attempt_no")).isEqualTo(1);
        assertThat(row.get("source_code")).isEqualTo("print(41 + 1)");
        assertThat(((Timestamp) row.get("submitted_at")).toInstant()).isEqualTo(ARRIVED);
        // Run again from the start: a new start and finish, not the failed run's.
        assertThat(((Timestamp) row.get("started_at")).toInstant()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(((Timestamp) row.get("finished_at")).toInstant()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    void anAdminMayReRunToo() throws Exception {
        long id = submission(1, "ERROR");

        assertThat(rerun(admin, id).statusCode()).isEqualTo(202);
        awaitStatus(id, "GRADED");
    }

    @Test
    void neitherThePassedDeadlineNorTheAttemptLimitIsJudgedAgain() throws Exception {
        // The failed attempt is the one the student already made: its ERROR gave the attempt back
        // (5.1d) and they used the limit since; the deadline has passed.
        jdbc.update(
                "update assignments set deadline = now() - interval '1 day', max_attempts = 1 where id = ?",
                assignment.getId());
        long failed = submission(1, "ERROR");
        submission(2, "GRADED");

        HttpResponse<String> response = rerun(owner, failed);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        awaitStatus(failed, "GRADED");
    }

    @Test
    void staleTestResultsAreDeletedSoAQueuedSubmissionHasNone() throws Exception {
        long id = submission(1, "ERROR");
        // A result left from a cut-short run, for a test of another assignment (this one has none, so
        // the new grading stores nothing and cannot replace it: only the re-run's delete removes it).
        Assignment other = assignments.save(new Assignment(
                assignment.getCourseId(),
                "Other",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        RubricCriterion criterion =
                rubricCriteria.save(new RubricCriterion(other.getId(), "T", CriterionType.TESTS, 100));
        TestCase test = testCases.save(
                new TestCase(other.getId(), criterion.getId(), "t", "1", "1", TestVisibility.PUBLIC, 1, 0));
        jdbc.update(
                "insert into test_results (submission_id, test_case_id, status) values (?, ?, 'FAILED')",
                id,
                test.getId());

        assertThat(rerun(owner, id).statusCode()).isEqualTo(202);

        awaitStatus(id, "GRADED");
        assertThat(jdbc.queryForObject("select count(*) from test_results where submission_id = ?", Integer.class, id))
                .isZero();
    }

    // --- refused -----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"GRADED", "RUNNING"})
    void aSubmissionInAnotherStateIsRefusedAndLeftAsItWas(String state) throws Exception {
        long id = submission(1, state);
        Map<String, Object> before = row(id);

        HttpResponse<String> response = rerun(owner, id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("SUBMISSION_NOT_IN_ERROR");
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void aQueuedSubmissionIsRefused() throws Exception {
        // Not compared afterwards: a QUEUED row may be taken by the worker at any moment.
        long id = submission(1, "QUEUED");

        HttpResponse<String> response = rerun(owner, id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("SUBMISSION_NOT_IN_ERROR");
    }

    @Test
    void anotherTeacherGetsTheSame404AsAMissingSubmissionAndChangesNothing() throws Exception {
        long id = submission(1, "ERROR");

        HttpResponse<String> refused = rerun(otherTeacher, id);
        HttpResponse<String> missing = rerun(otherTeacher, 987654321L);

        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(code(refused)).isEqualTo("SUBMISSION_NOT_FOUND");
        assertThat(refused.body()).isEqualTo(missing.body());
        assertThat(status(id)).isEqualTo("ERROR");
    }

    @Test
    void aStudentIsRefusedByRoleEvenForTheirOwnSubmission() throws Exception {
        long id = submission(1, "ERROR");

        HttpResponse<String> response = rerun(student, id);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(status(id)).isEqualTo("ERROR");
    }

    @Test
    void anonymousIsUnauthenticated() throws Exception {
        long id = submission(1, "ERROR");

        assertThat(rerun(null, id).statusCode()).isEqualTo(401);
        assertThat(status(id)).isEqualTo("ERROR");
    }

    @Test
    void ofTwoReRunsAtOnceExactlyOneWins() throws Exception {
        long id = submission(1, "ERROR");
        String token = tokenFor(owner);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return send("POST", "/submissions/" + id + "/rerun", token, null)
                            .statusCode();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        } finally {
            pool.shutdownNow();
        }
        awaitStatus(id, "GRADED");
    }
}
