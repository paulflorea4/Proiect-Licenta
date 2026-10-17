package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.repository.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 5.2c: when the recovery's two steps run and what each does, against mocks. What they do to real
 * rows is in {@code RealHttpSubmissionWorkerTests}.
 */
class SubmissionRecoveryTests {

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final SubmissionDispatcher dispatcher = mock(SubmissionDispatcher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SubmissionRecovery recovery = new SubmissionRecovery(submissions, dispatcher, transactions);

    @Test
    void theStepsAreSpringStartupHooks() {
        // Step one before the server takes requests, step two after the sandbox sweep (4.3e).
        assertThat(recovery).isInstanceOf(SmartInitializingSingleton.class).isInstanceOf(ApplicationRunner.class);
    }

    @Test
    void stepOneDropsPartialResultsThenRequeuesInOneTransactionAndOffersNothing() {
        TransactionStatus tx = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(tx);
        when(submissions.requeueRunning()).thenReturn(3);

        recovery.afterSingletonsInstantiated();

        InOrder order = inOrder(transactions, submissions);
        order.verify(transactions).getTransaction(any());
        order.verify(submissions).deleteTestResultsOfRunning();
        order.verify(submissions).requeueRunning();
        order.verify(transactions).commit(tx);
        verify(dispatcher, never()).pickUpQueued();
    }

    @Test
    void stepTwoOffersTheWaitingSubmissionsAndChangesNoRow() {
        recovery.run(null);

        verify(dispatcher).pickUpQueued();
        verify(submissions, never()).requeueRunning();
        verify(submissions, never()).deleteTestResultsOfRunning();
    }
}
