package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.service.SubmissionProcessor;
import com.gradingplatform.backend.service.SubmissionQueuedEvent;
import com.gradingplatform.backend.service.SubmissionRecovery;
import com.gradingplatform.backend.service.SubmissionWorker;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 5.2a: accepted submissions are picked up by the bounded worker pool against the real database. A
 * pool of one with a queue of one, and a processor that holds each claimed submission until the test
 * lets it go, so who is {@code RUNNING} and who is still {@code QUEUED} can be seen at every step.
 * 5.2b: once let go a submission is {@code GRADED}, or {@code ERROR} when the processor throws, with
 * {@code finished_at}, and the student's code is kept. 5.2c: after a restart, what was left {@code
 * RUNNING} is {@code QUEUED} again without its partial results, and everything waiting is graded
 * (the recovery's two startup steps are called here as Spring calls them at startup).
 */
@Import({TestcontainersConfiguration.class, RealHttpSubmissionWorkerTests.Holding.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"submissions.worker.pool-size=1", "submissions.worker.queue-capacity=1"})
class RealHttpSubmissionWorkerTests extends RealHttpTestBase {

    private static final Instant DEADLINE = Instant.parse("2031-06-30T21:59:59Z");

    /**
     * Records each submission it is given and holds it until a permit is released; then throws
     * {@link #failure} if one is set.
     */
    static class HoldingProcessor implements SubmissionProcessor {

        volatile Throwable failure;

        final Semaphore permits = new Semaphore(0);
        final List<Long> processed = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();

        @Override
        public void process(long submissionId) {
            // Counted as running before it is seen as taken: letGo() waits for running == 0, and a
            // submission seen as taken but not yet counted would let letGo() return early, then wait
            // for a permit after the next test's reset() drained them, holding the only worker.
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            processed.add(submissionId);
            try {
                permits.tryAcquire(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            Throwable thrown = failure;
            if (thrown instanceof RuntimeException e) {
                throw e;
            }
            if (thrown instanceof Error e) {
                throw e;
            }
        }

        void reset() {
            failure = null;
            permits.drainPermits();
            processed.clear();
            mostAtOnce.set(0);
        }
    }

    @TestConfiguration
    static class Holding {

        @Bean
        @Primary
        HoldingProcessor holdingProcessor() {
            return new HoldingProcessor();
        }
    }

    @Autowired
    private HoldingProcessor processor;

    @Autowired
    private SubmissionWorker worker;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private SubmissionRecovery recovery;

    private User student;
    private Assignment assignment;

    @BeforeEach
    void world() {
        processor.reset();
        User owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(
                new Assignment(course.getId(), "Sorting", "d", "PYTHON", DEADLINE, null, 2000, 256, null));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
    }

    /** Lets every held submission go, so no worker is left waiting into the next test. */
    @AfterEach
    void letGo() throws InterruptedException {
        processor.permits.release(1000);
        eventually("the worker is idle", () -> processor.running.get() == 0);
        jdbc.update("delete from test_results");
    }

    /** A submission stored as a restart would find it, without going through the pool. */
    private long left(SubmissionStatus status) {
        int attemptNo = submissions.nextAttemptNo(assignment.getId(), student.getId());
        Submission row = submissions.save(
                new Submission(assignment.getId(), student.getId(), "PYTHON", "print(1)", attemptNo, Instant.now()));
        if (status != SubmissionStatus.QUEUED) {
            jdbc.update(
                    "update submissions set status = ?, started_at = now() where id = ?", status.name(), row.getId());
        }
        return row.getId();
    }

    private long testCase(String name) {
        Long criterion = jdbc.queryForObject(
                "insert into rubric_criteria (assignment_id, name, type, weight) values (?, 'Tests', 'TESTS', 100)"
                        + " returning id",
                Long.class,
                assignment.getId());
        return jdbc.queryForObject(
                "insert into test_cases (assignment_id, criterion_id, name, input, expected_output, visibility,"
                        + " position) values (?, ?, ?, '', '1', 'PUBLIC', 0) returning id",
                Long.class,
                assignment.getId(),
                criterion,
                name);
    }

    private void result(long submissionId, long testCaseId) {
        jdbc.update(
                "insert into test_results (submission_id, test_case_id, status) values (?, ?, 'PASSED')",
                submissionId,
                testCaseId);
    }

    private int results(long submissionId) {
        return jdbc.queryForObject(
                "select count(*) from test_results where submission_id = ?", Integer.class, submissionId);
    }

    private long submit() throws Exception {
        HttpResponse<String> response = post(
                "/assignments/" + assignment.getId() + "/submissions",
                "{\"sourceCode\":\"print(1)\"}",
                tokenFor(student));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("QUEUED");
        return JsonPath.<Number>read(response.body(), "$.id").longValue();
    }

    private Submission stored(long id) {
        return submissions.findById(id).orElseThrow();
    }

    private SubmissionStatus status(long id) {
        return stored(id).getStatus();
    }

    private void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("timed out waiting: " + what + "\n" + state());
            }
            Thread.sleep(20);
        }
    }

    /** What the worker side looks like, for a timeout's message: a wait that never ends says why. */
    private String state() {
        StringBuilder state = new StringBuilder()
                .append("processed=")
                .append(processor.processed)
                .append(" running=")
                .append(processor.running.get())
                .append(" permits=")
                .append(processor.permits.availablePermits())
                .append("\nsubmissions (id, status, started_at, finished_at)=")
                .append(jdbc.queryForList("select id, status, started_at, finished_at from submissions order by id"));
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (thread.getName().startsWith("submission-worker")) {
                state.append("\n").append(thread.getName()).append(" ").append(thread.getState());
                for (StackTraceElement frame : stack) {
                    state.append("\n    at ").append(frame);
                }
            }
        });
        return state.toString();
    }

    @Test
    void aBurstIsGradedOneAtATimeInOrderAndTheRestWaitsQueued() throws Exception {
        long first = submit();
        eventually("the first is taken", () -> processor.processed.size() == 1);
        long second = submit();
        long third = submit();
        long fourth = submit();

        Thread.sleep(200);
        assertThat(processor.processed).containsExactly(first);
        assertThat(status(first)).isEqualTo(SubmissionStatus.RUNNING);
        assertThat(stored(first).getStartedAt()).isNotNull();
        for (long waiting : List.of(second, third, fourth)) {
            assertThat(status(waiting))
                    .as("waiting, in memory or in the table only")
                    .isEqualTo(SubmissionStatus.QUEUED);
            assertThat(stored(waiting).getStartedAt()).isNull();
        }

        for (int done = 1; done <= 3; done++) {
            processor.permits.release();
            int next = done + 1;
            eventually("the next one is taken", () -> processor.processed.size() == next);
        }

        assertThat(processor.processed).containsExactly(first, second, third, fourth);
        assertThat(processor.mostAtOnce.get()).as("a pool of one").isEqualTo(1);
        for (long done : List.of(first, second, third)) {
            assertThat(status(done)).as("let go, so graded").isEqualTo(SubmissionStatus.GRADED);
        }
        assertThat(status(fourth)).as("still held").isEqualTo(SubmissionStatus.RUNNING);
        assertThat(stored(fourth).getFinishedAt()).isNull();
    }

    @Test
    void startedAtIsTheApplicationClockWhenTheWorkerTakesIt() throws Exception {
        Instant now = Instant.parse("2030-01-02T03:04:05.123456Z");
        clock.set(now);

        long id = submit();
        eventually("taken", () -> processor.processed.contains(id));

        assertThat(stored(id).getStartedAt()).isEqualTo(now);
        assertThat(stored(id).getFinishedAt()).isNull();
    }

    @Test
    void aSubmissionThatIsNoLongerQueuedIsNeverTakenAgain() throws Exception {
        long id = submit();
        eventually("taken", () -> processor.processed.contains(id));
        Instant startedAt = stored(id).getStartedAt();

        worker.work(id); // RUNNING
        assertThat(status(id)).isEqualTo(SubmissionStatus.RUNNING);

        processor.permits.release();
        eventually("graded", () -> status(id) == SubmissionStatus.GRADED);
        Instant finishedAt = stored(id).getFinishedAt();

        worker.work(id); // GRADED
        jdbc.update("update submissions set status = 'ERROR' where id = ?", id);
        worker.work(id); // ERROR
        worker.work(Long.MAX_VALUE); // no such submission

        assertThat(processor.processed).containsExactly(id);
        assertThat(stored(id).getStartedAt()).isEqualTo(startedAt);
        assertThat(stored(id).getFinishedAt()).isEqualTo(finishedAt);
        assertThat(status(id)).isEqualTo(SubmissionStatus.ERROR);
    }

    @Test
    void aProcessedSubmissionIsGradedWithFinishedAtFromTheApplicationClock() throws Exception {
        Instant started = Instant.parse("2030-01-02T03:04:05.123456Z");
        Instant finished = Instant.parse("2030-01-02T03:04:17.654321Z");
        clock.set(started);
        long id = submit();
        eventually("taken", () -> processor.processed.contains(id));

        clock.set(finished);
        processor.permits.release();
        eventually("graded", () -> status(id) == SubmissionStatus.GRADED);

        assertThat(stored(id).getStartedAt()).isEqualTo(started);
        assertThat(stored(id).getFinishedAt()).isEqualTo(finished);
    }

    @Test
    void anExceptionWhileGradingEndsInErrorWithTheSubmissionKept() throws Exception {
        jdbc.update("update assignments set max_attempts = 1 where id = ?", assignment.getId());
        Instant finished = Instant.parse("2030-01-02T03:04:17.654321Z");
        long id = submit();
        eventually("taken", () -> processor.processed.contains(id));
        Submission before = stored(id);

        processor.failure = new IllegalStateException("grading crashed");
        clock.set(finished);
        processor.permits.release();
        eventually("ended", () -> status(id) == SubmissionStatus.ERROR);

        Submission after = stored(id);
        assertThat(after.getFinishedAt()).isEqualTo(finished);
        assertThat(after.getSourceCode()).isEqualTo("print(1)");
        assertThat(after.getLanguage()).isEqualTo("PYTHON");
        assertThat(after.getAttemptNo()).isEqualTo(before.getAttemptNo());
        assertThat(after.getSubmittedAt()).isEqualTo(before.getSubmittedAt());
        assertThat(after.getStartedAt()).isEqualTo(before.getStartedAt());

        // The worker carries on, and the ERROR gave the only attempt back (5.1d).
        processor.failure = null;
        long next = submit();
        eventually("taken", () -> processor.processed.contains(next));
        processor.permits.release();
        eventually("graded", () -> status(next) == SubmissionStatus.GRADED);
        assertThat(status(id)).isEqualTo(SubmissionStatus.ERROR);
    }

    @Test
    void anErrorWhileGradingAlsoEndsInErrorAndThePoolCarriesOn() throws Exception {
        long id = submit();
        eventually("taken", () -> processor.processed.contains(id));
        processor.failure = new StackOverflowError("thrown by the test processor");
        processor.permits.release();
        eventually("ended", () -> status(id) == SubmissionStatus.ERROR);
        assertThat(stored(id).getFinishedAt()).isNotNull();

        processor.failure = null;
        long next = submit();
        eventually("taken by the replacement thread", () -> processor.processed.contains(next));
        processor.permits.release();
        eventually("graded", () -> status(next) == SubmissionStatus.GRADED);
    }

    @Test
    void onlyARunningSubmissionCanBeFinished() {
        Instant at = Instant.parse("2030-01-02T03:04:05.123456Z");
        for (SubmissionStatus from : SubmissionStatus.values()) {
            for (SubmissionStatus to : List.of(SubmissionStatus.GRADED, SubmissionStatus.ERROR)) {
                Submission row = submissions.save(
                        new Submission(assignment.getId(), student.getId(), "PYTHON", "print(1)", 1, Instant.now()));
                jdbc.update("update submissions set status = ? where id = ?", from.name(), row.getId());

                int changed = submissions.finish(row.getId(), to, at);

                Submission after = stored(row.getId());
                if (from == SubmissionStatus.RUNNING) {
                    assertThat(changed).as(from + " to " + to).isEqualTo(1);
                    assertThat(after.getStatus()).isEqualTo(to);
                    assertThat(after.getFinishedAt()).isEqualTo(at);
                } else {
                    assertThat(changed).as(from + " to " + to).isZero();
                    assertThat(after.getStatus()).isEqualTo(from);
                    assertThat(after.getFinishedAt()).isNull();
                }
                assertThat(after.getSourceCode()).isEqualTo("print(1)");
                submissions.delete(after);
            }
        }
        assertThat(submissions.finish(Long.MAX_VALUE, SubmissionStatus.GRADED, at))
                .isZero();
    }

    @Test
    void aSubmissionIsHandedToTheWorkerOnlyOnceItsTransactionHasCommitted() throws Exception {
        long id = new TransactionTemplate(transactions).execute(tx -> {
            Submission row = submissions.saveAndFlush(
                    new Submission(assignment.getId(), student.getId(), "PYTHON", "print(1)", 1, Instant.now()));
            events.publishEvent(new SubmissionQueuedEvent(row.getId()));
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(processor.processed).as("not before the commit").isEmpty();
            return row.getId();
        });

        eventually("taken after the commit", () -> processor.processed.contains(id));
        assertThat(status(id)).isEqualTo(SubmissionStatus.RUNNING);
    }

    @Test
    void aRefusedSubmissionIsNeverHandedToTheWorker() throws Exception {
        jdbc.update("update assignments set max_attempts = 1 where id = ?", assignment.getId());
        long id = submit();
        eventually("taken", () -> processor.processed.contains(id));
        processor.permits.release();

        HttpResponse<String> refused = post(
                "/assignments/" + assignment.getId() + "/submissions",
                "{\"sourceCode\":\"print(2)\"}",
                tokenFor(student));
        assertThat(refused.statusCode()).isEqualTo(409);

        Thread.sleep(200);
        assertThat(processor.processed).containsExactly(id);
        assertThat(submissions.count()).isEqualTo(1);
    }

    @Test
    void aRestartRequeuesWhatWasRunningWithoutItsPartialResults() throws Exception {
        long first = testCase("first");
        long second = testCase("second");
        long running = left(SubmissionStatus.RUNNING);
        result(running, first);
        long queued = left(SubmissionStatus.QUEUED);
        long graded = left(SubmissionStatus.GRADED);
        result(graded, first);
        result(graded, second);
        long error = left(SubmissionStatus.ERROR);
        Submission before = stored(running);
        Instant gradedStartedAt = stored(graded).getStartedAt();

        recovery.afterSingletonsInstantiated();

        Submission after = stored(running);
        assertThat(after.getStatus()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(after.getStartedAt()).isNull();
        assertThat(after.getFinishedAt()).isNull();
        assertThat(after.getSourceCode()).isEqualTo(before.getSourceCode());
        assertThat(after.getAttemptNo()).isEqualTo(before.getAttemptNo());
        assertThat(after.getSubmittedAt()).isEqualTo(before.getSubmittedAt());
        assertThat(results(running)).as("partial results dropped").isZero();

        assertThat(status(queued)).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(status(graded)).isEqualTo(SubmissionStatus.GRADED);
        assertThat(stored(graded).getStartedAt()).isEqualTo(gradedStartedAt);
        assertThat(results(graded)).as("a finished grading keeps its results").isEqualTo(2);
        assertThat(status(error)).isEqualTo(SubmissionStatus.ERROR);

        Thread.sleep(200);
        assertThat(processor.processed)
                .as("step one offers nothing to the pool")
                .isEmpty();
    }

    @Test
    void afterARestartEverythingWaitingIsGradedOldestFirst() throws Exception {
        long q1 = left(SubmissionStatus.QUEUED);
        long r1 = left(SubmissionStatus.RUNNING);
        long q2 = left(SubmissionStatus.QUEUED);
        long r2 = left(SubmissionStatus.RUNNING);
        long done = left(SubmissionStatus.GRADED);

        recovery.afterSingletonsInstantiated();
        recovery.run(null);

        List<Long> order = List.of(q1, r1, q2, r2);
        for (int i = 1; i <= order.size(); i++) {
            int taken = i;
            eventually("the next one is taken", () -> processor.processed.size() == taken);
            processor.permits.release();
        }
        for (long id : order) {
            eventually("graded", () -> status(id) == SubmissionStatus.GRADED);
        }
        assertThat(processor.processed).containsExactlyElementsOf(order);
        assertThat(processor.processed).doesNotContain(done);
        assertThat(processor.mostAtOnce.get()).as("still a pool of one").isEqualTo(1);
    }

    @Test
    void recoveringTwiceGradesEachSubmissionOnce() throws Exception {
        long testCase = testCase("only");
        long running = left(SubmissionStatus.RUNNING);
        result(running, testCase);

        recovery.afterSingletonsInstantiated();
        recovery.afterSingletonsInstantiated();
        recovery.run(null);
        recovery.run(null);

        eventually("taken", () -> processor.processed.contains(running));
        processor.permits.release();
        eventually("graded", () -> status(running) == SubmissionStatus.GRADED);
        Thread.sleep(200);
        assertThat(processor.processed).containsExactly(running);
        assertThat(results(running)).isZero();
    }
}
