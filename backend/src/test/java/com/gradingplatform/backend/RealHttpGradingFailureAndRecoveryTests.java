package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.sandbox.SandboxRequest;
import com.gradingplatform.backend.sandbox.SandboxResult;
import com.gradingplatform.backend.sandbox.SandboxRunner;
import com.gradingplatform.backend.sandbox.SandboxStatus;
import com.gradingplatform.backend.service.SubmissionRecovery;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 5.6d: restart recovery (5.2c) and the promise that a crash while grading loses nothing (5.2b),
 * tested through the real chain: the HTTP endpoints, the dispatcher and worker, the application's
 * own {@code SubmissionGrader} and {@code SubmissionResultWriter}, and the recovery's two startup
 * steps (called as Spring calls them, as in {@code RealHttpSubmissionWorkerTests}). Only the
 * sandbox is replaced, by {@link ScriptedSandbox}, so a run can be made to fail on demand and no
 * Docker is needed; what the sandbox does when a container really fails is the 4.x tests' job.
 *
 * <p>The assignment: a TESTS criterion of 60 over two tests of weight 1 (expecting {@code 1} and
 * {@code 2}) and a MANUAL one of 40. The scripted sandbox prints {@code 1} for a good program, so
 * the first test passes and the second fails: TESTS = 60 × 1/2 = 30.00 (by hand), MANUAL pending.
 */
@Import({TestcontainersConfiguration.class, RealHttpGradingFailureAndRecoveryTests.Scripted.class})
class RealHttpGradingFailureAndRecoveryTests extends RealHttpTestBase {

    private static final Instant DEADLINE = Instant.parse("2031-06-30T21:59:59Z");
    private static final String GOOD = "print(1)";

    /**
     * A sandbox that obeys the program text: {@code CRASH} throws an unexpected exception, {@code
     * STACK} a {@code StackOverflowError}, {@code SANDBOX} reports a sandbox failure; anything else
     * runs "cleanly" and prints {@code 1}.
     */
    static class ScriptedSandbox implements SandboxRunner {

        @Override
        public SandboxResult run(SandboxRequest request) {
            String code = request.sourceFiles().get(0).content();
            if (code.contains("CRASH")) {
                throw new IllegalStateException("scripted crash");
            }
            if (code.contains("STACK")) {
                throw new StackOverflowError("scripted error");
            }
            if (code.contains("SANDBOX")) {
                return new SandboxResult(SandboxStatus.SANDBOX_ERROR, null, "", "", false, false, Duration.ZERO);
            }
            return new SandboxResult(SandboxStatus.OK, 0, "1\n", "", false, false, Duration.ofMillis(5));
        }
    }

    @TestConfiguration
    static class Scripted {

        @Bean
        @Primary
        SandboxRunner scriptedSandbox() {
            return new ScriptedSandbox();
        }
    }

    @Autowired
    private SubmissionRecovery recovery;

    private User owner;
    private User student;
    private Assignment assignment;
    private RubricCriterion manual;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment =
                assignments.save(new Assignment(course.getId(), "Sum", "d", "PYTHON", DEADLINE, null, 2000, 128, null));
        RubricCriterion tests =
                rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 60));
        manual = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Style", CriterionType.MANUAL, 40));
        testCases.save(new TestCase(assignment.getId(), tests.getId(), "one", "", "1", TestVisibility.PUBLIC, 1, 1));
        testCases.save(new TestCase(assignment.getId(), tests.getId(), "two", "", "2", TestVisibility.HIDDEN, 1, 2));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
    }

    // ---- helpers ----

    private long submit(String code) throws Exception {
        HttpResponse<String> response = post(
                "/assignments/" + assignment.getId() + "/submissions",
                "{\"sourceCode\":\"" + code + "\"}",
                tokenFor(student));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return JsonPath.<Number>read(response.body(), "$.id").longValue();
    }

    private SubmissionStatus status(long id) {
        return submissions.findById(id).orElseThrow().getStatus();
    }

    /** Waits until the submission is {@code GRADED} or {@code ERROR} and returns which. */
    private SubmissionStatus awaitEnd(long id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            SubmissionStatus status = status(id);
            if (status == SubmissionStatus.GRADED || status == SubmissionStatus.ERROR) {
                return status;
            }
            if (System.nanoTime() > deadline) {
                fail("submission %d still %s after 30 s", id, status);
            }
            Thread.sleep(25);
        }
    }

    private int count(String table, long submissionId) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where submission_id = ?", Integer.class, submissionId);
    }

    /** What the worker leaves behind for a submission that ended in {@code ERROR}: nothing but the row. */
    private void assertNothingStored(long id) {
        assertThat(count("test_results", id)).as("test results").isZero();
        assertThat(count("grades", id)).as("grade").isZero();
        assertThat(count("criterion_scores", id)).as("criterion scores").isZero();
    }

    private String studentView(long id) throws Exception {
        HttpResponse<String> response = get("/submissions/" + id, tokenFor(student));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private void assertGraded30(long id) {
        // One passed (weight 1) and one failed (weight 1): 60 × 1/2 = 30.00; Style is pending.
        assertThat(count("test_results", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select total_score from grades where submission_id = ?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("30.00");
    }

    /** A submission stored as a restart would find it, without going through the pool. */
    private long left(String code, SubmissionStatus status) {
        int attemptNo = submissions.nextAttemptNo(assignment.getId(), student.getId());
        long id = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status,"
                        + " started_at) values (?, ?, 'PYTHON', ?, ?, ?, case when ? = 'QUEUED' then null else now() end)"
                        + " returning id",
                Long.class,
                assignment.getId(),
                student.getId(),
                code,
                attemptNo,
                status.name(),
                status.name());
        return id;
    }

    private void restart() {
        recovery.afterSingletonsInstantiated();
        recovery.run(null);
    }

    // ---- 5.2b: execution throws ----

    @ParameterizedTest
    @ValueSource(strings = {"SANDBOX", "CRASH", "STACK"})
    void whateverGoesWrongWhileRunningTheSubmissionEndsInErrorWithTheCodeKeptAndNothingStored(String failure)
            throws Exception {
        jdbc.update("update assignments set max_attempts = 1 where id = ?", assignment.getId());
        String code = "print(1) # " + failure;

        long id = submit(code);

        assertThat(awaitEnd(id)).isEqualTo(SubmissionStatus.ERROR);
        var stored = submissions.findById(id).orElseThrow();
        assertThat(stored.getSourceCode()).isEqualTo(code);
        assertThat(stored.getAttemptNo()).isEqualTo(1);
        assertThat(stored.getStartedAt()).isNotNull();
        assertThat(stored.getFinishedAt()).isNotNull();
        assertNothingStored(id);
        // The student still sees their own work, and no result.
        String view = studentView(id);
        assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("ERROR");
        assertThat(JsonPath.<String>read(view, "$.sourceCode")).isEqualTo(code);
        assertThat(JsonPath.<Object>read(view, "$.grade")).isNull();
        assertThat(JsonPath.<List<Object>>read(view, "$.tests")).isEmpty();

        // The worker (a replacement thread after the Error) carries on, and the one attempt is back (5.1d).
        long next = submit(GOOD);
        assertThat(awaitEnd(next)).isEqualTo(SubmissionStatus.GRADED);
        assertGraded30(next);
        assertThat(status(id)).as("the failed one stays failed").isEqualTo(SubmissionStatus.ERROR);
    }

    @Test
    void aBurstWhereSomeRunsFailLosesNoSubmission() throws Exception {
        String[] failures = {"CRASH", "SANDBOX", "STACK"};
        List<Long> ids = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // Every third submission is good, the others fail in turn in each of the three ways.
            String code = i % 3 == 2 ? GOOD + " # " + i : "print(1) # " + failures[i / 3 % 3] + " " + i;
            codes.add(code);
            ids.add(submit(code));
        }

        List<SubmissionStatus> ends = new ArrayList<>();
        for (long id : ids) {
            ends.add(awaitEnd(id));
        }

        assertThat(ends).hasSize(12);
        for (int i = 0; i < 12; i++) {
            long id = ids.get(i);
            boolean good = i % 3 == 2;
            assertThat(ends.get(i))
                    .as("submission %d (%s)", i, codes.get(i))
                    .isEqualTo(good ? SubmissionStatus.GRADED : SubmissionStatus.ERROR);
            assertThat(submissions.findById(id).orElseThrow().getSourceCode()).isEqualTo(codes.get(i));
            if (good) {
                assertGraded30(id);
            } else {
                assertNothingStored(id);
            }
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from submissions where status in ('QUEUED', 'RUNNING')", Integer.class))
                .as("nothing left waiting or running")
                .isZero();
        assertThat(jdbc.queryForList("select attempt_no from submissions order by id", Integer.class))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    // ---- 5.2c: restart ----

    @Test
    void aRestartGradesWhatWasLeftQueuedOrRunningAndEndsWhatCannotBeGraded() throws Exception {
        long staleResult = jdbc.queryForObject(
                "select id from test_cases where name = 'one' and assignment_id = ?", Long.class, assignment.getId());
        long queuedGood = left(GOOD + " # queued", SubmissionStatus.QUEUED);
        long runningGood = left(GOOD + " # running", SubmissionStatus.RUNNING);
        jdbc.update(
                "insert into test_results (submission_id, test_case_id, status) values (?, ?, 'PASSED')",
                runningGood,
                staleResult);
        long runningThatFailsAgain = left("print(1) # SANDBOX", SubmissionStatus.RUNNING);
        jdbc.update(
                "insert into test_results (submission_id, test_case_id, status) values (?, ?, 'PASSED')",
                runningThatFailsAgain,
                staleResult);
        long queuedThatCrashes = left("print(1) # CRASH", SubmissionStatus.QUEUED);
        long alreadyGraded = left(GOOD + " # done", SubmissionStatus.GRADED);
        Instant gradedFinishedAt = Instant.parse("2030-01-01T00:00:00Z");
        jdbc.update(
                "update submissions set finished_at = ? where id = ?",
                java.sql.Timestamp.from(gradedFinishedAt),
                alreadyGraded);

        restart();

        assertThat(awaitEnd(queuedGood)).isEqualTo(SubmissionStatus.GRADED);
        assertThat(awaitEnd(runningGood)).isEqualTo(SubmissionStatus.GRADED);
        assertThat(awaitEnd(runningThatFailsAgain)).isEqualTo(SubmissionStatus.ERROR);
        assertThat(awaitEnd(queuedThatCrashes)).isEqualTo(SubmissionStatus.ERROR);
        assertGraded30(queuedGood);
        // The stale partial result was dropped and replaced, not added to: two results, not three.
        assertGraded30(runningGood);
        assertNothingStored(runningThatFailsAgain);
        assertNothingStored(queuedThatCrashes);
        for (long id : List.of(queuedGood, runningGood, runningThatFailsAgain, queuedThatCrashes)) {
            assertThat(submissions.findById(id).orElseThrow().getFinishedAt()).isNotNull();
        }
        var untouched = submissions.findById(alreadyGraded).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(SubmissionStatus.GRADED);
        assertThat(untouched.getFinishedAt()).isEqualTo(gradedFinishedAt);
        assertThat(count("test_results", alreadyGraded))
                .as("a graded one is never run again")
                .isZero();
    }

    @Test
    void aCrashAfterTheGradeWasCommittedIsRegradedWithoutDuplicatesAndKeepsTheTeachersScore() throws Exception {
        long id = submit(GOOD);
        assertThat(awaitEnd(id)).isEqualTo(SubmissionStatus.GRADED);
        // The teacher scores Style: 10 of 40. Total 30.00 + 10.00 = 40.00, nothing pending.
        HttpResponse<String> scored = send(
                "PUT",
                "/submissions/" + id + "/manual-scores/" + manual.getId(),
                tokenFor(owner),
                "{\"score\":10,\"comment\":\"tidy\"}");
        assertThat(scored.statusCode()).as(scored.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select total_score from grades where submission_id = ?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("40.00");

        // The crash: everything was committed, but the process died before the status was written. The
        // grade it left is wrong on purpose, to show that it is replaced and not kept.
        jdbc.update("update submissions set status = 'RUNNING', finished_at = null where id = ?", id);
        jdbc.update("update grades set total_score = 99.00 where submission_id = ?", id);

        restart();

        assertThat(awaitEnd(id)).isEqualTo(SubmissionStatus.GRADED);
        assertThat(count("grades", id)).as("one grade, not two").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select total_score from grades where submission_id = ?", java.math.BigDecimal.class, id))
                .as("the stale grade is replaced by the recomputed one: 30.00 + the teacher's 10.00")
                .isEqualByComparingTo("40.00");
        assertThat(count("test_results", id))
                .as("results replaced, not doubled")
                .isEqualTo(2);
        assertThat(count("criterion_scores", id))
                .as("the TESTS row and the teacher's row")
                .isEqualTo(2);
        Map<String, Object> teachers = jdbc.queryForMap(
                "select score, comment from criterion_scores where submission_id = ? and criterion_id = ?",
                id,
                manual.getId());
        assertThat(teachers.get("score")).isEqualTo(new java.math.BigDecimal("10.00"));
        assertThat(teachers.get("comment")).isEqualTo("tidy");
        assertThat(jdbc.queryForObject("select provisional from grades where submission_id = ?", Boolean.class, id))
                .isFalse();
    }

    @Test
    void recoveringWhileNothingIsLeftChangesNothing() throws Exception {
        long id = submit(GOOD);
        assertThat(awaitEnd(id)).isEqualTo(SubmissionStatus.GRADED);
        List<Map<String, Object>> before = jdbc.queryForList("select * from submissions order by id");

        restart();
        Thread.sleep(200);

        assertThat(jdbc.queryForList("select * from submissions order by id")).isEqualTo(before);
        assertGraded30(id);
    }
}
