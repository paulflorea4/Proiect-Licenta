package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.service.SubmissionProcessor;
import com.gradingplatform.backend.service.SubmissionQueuedEvent;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 5.2a: accepted submissions are picked up by the bounded worker pool against the real database. A
 * pool of one with a queue of one, and a processor that holds each claimed submission until the test
 * lets it go, so who is {@code RUNNING} and who is still {@code QUEUED} can be seen at every step.
 */
@Import({TestcontainersConfiguration.class, RealHttpSubmissionWorkerTests.Holding.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"submissions.worker.pool-size=1", "submissions.worker.queue-capacity=1"})
class RealHttpSubmissionWorkerTests extends RealHttpTestBase {

    private static final Instant DEADLINE = Instant.parse("2031-06-30T21:59:59Z");

    /** Records each submission it is given and holds it until a permit is released. */
    static class HoldingProcessor implements SubmissionProcessor {

        final Semaphore permits = new Semaphore(0);
        final List<Long> processed = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();

        @Override
        public void process(long submissionId) {
            processed.add(submissionId);
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                permits.tryAcquire(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }

        void reset() {
            permits.drainPermits();
            processed.clear();
            mostAtOnce.set(0);
        }
    }

    @TestConfiguration
    static class Holding {

        @Bean
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

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(20);
        }
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
        for (long id : List.of(first, second, third, fourth)) {
            assertThat(status(id)).as("claimed; grading itself comes later").isEqualTo(SubmissionStatus.RUNNING);
        }
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
        processor.permits.release();
        eventually("done", () -> processor.running.get() == 0);

        worker.work(id); // RUNNING already
        jdbc.update("update submissions set status = 'ERROR' where id = ?", id);
        worker.work(id); // finished
        worker.work(Long.MAX_VALUE); // no such submission

        assertThat(processor.processed).containsExactly(id);
        assertThat(stored(id).getStartedAt()).isEqualTo(startedAt);
        assertThat(status(id)).isEqualTo(SubmissionStatus.ERROR);
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
}
