package com.gradingplatform.backend.service;

import com.gradingplatform.backend.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup recovery (5.2c): what a stop or crash left {@code QUEUED} or {@code RUNNING} is graded
 * after the restart, in two steps at two moments.
 *
 * <ol>
 *   <li>{@link #requeueInterrupted} when every bean exists, before the server takes a request (so no
 *       worker of this process can have claimed anything yet): every {@code RUNNING} submission loses
 *       its partial {@code test_results} and is {@code QUEUED} again, in one transaction. A
 *       submission is never graded on top of an interrupted run's results, and every {@code QUEUED}
 *       row has none.
 *   <li>{@link #run} as an {@code ApplicationRunner}, so after the sandbox startup sweep (4.3e),
 *       which removes every sandbox container and must never see a run of this process: the oldest
 *       {@code QUEUED} rows are offered to the pool ({@link SubmissionDispatcher#pickUpQueued}), and
 *       the rest follow as workers take tasks, as for any row the pool once refused (5.2a).
 * </ol>
 *
 * <p>Both steps are idempotent: a second call finds nothing {@code RUNNING} from before, and the
 * dispatcher never hands one submission over twice at once. One backend instance is assumed (as for
 * the sweep): a second instance's {@code RUNNING} rows would be taken from it.
 */
@Component
public class SubmissionRecovery implements SmartInitializingSingleton, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubmissionRecovery.class);

    private final SubmissionRepository submissions;
    private final SubmissionDispatcher dispatcher;
    private final TransactionTemplate transaction;

    public SubmissionRecovery(
            SubmissionRepository submissions,
            SubmissionDispatcher dispatcher,
            PlatformTransactionManager transactions) {
        this.submissions = submissions;
        this.dispatcher = dispatcher;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public void afterSingletonsInstantiated() {
        requeueInterrupted();
    }

    @Override
    public void run(ApplicationArguments args) {
        dispatcher.pickUpQueued();
    }

    /**
     * Puts every {@code RUNNING} submission back to {@code QUEUED} without its partial results.
     *
     * @return how many there were
     */
    int requeueInterrupted() {
        Integer requeued = transaction.execute(tx -> {
            submissions.deleteTestResultsOfRunning();
            return submissions.requeueRunning();
        });
        if (requeued != null && requeued > 0) {
            log.info("Re-queued {} submission(s) a restart interrupted", requeued);
        }
        return requeued == null ? 0 : requeued;
    }
}
