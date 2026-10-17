package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.repository.SubmissionRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 5.2b: the worker's status changes on their own, against a mocked repository. Whatever the
 * processor does, a claimed submission is finished exactly once, {@code GRADED} when it returns and
 * {@code ERROR} when it throws. The same against the real database is in {@code
 * RealHttpSubmissionWorkerTests}.
 */
class SubmissionWorkerTests {

    private static final long ID = 42;
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2030-01-02T03:04:05.123456Z");

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final List<Long> calls = new ArrayList<>();

    private SubmissionWorker worker(SubmissionProcessor processor) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (processor != null) {
            beans.addBean("processor", processor);
        }
        ObjectProvider<SubmissionProcessor> provider = beans.getBeanProvider(SubmissionProcessor.class);
        return new SubmissionWorker(submissions, provider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void claimable() {
        when(submissions.claim(anyLong(), any())).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    void aSubmissionThatIsProcessedIsGradedAfterwards() {
        claimable();
        SubmissionProcessor processor = mock(SubmissionProcessor.class);

        worker(processor).work(ID);

        InOrder order = inOrder(submissions, processor);
        order.verify(submissions).claim(ID, NOW_MICROS);
        order.verify(processor).process(ID);
        order.verify(submissions).finish(ID, SubmissionStatus.GRADED, NOW_MICROS);
        verify(submissions, never()).finish(anyLong(), eq(SubmissionStatus.ERROR), any());
    }

    @Test
    void anExceptionWhileProcessingEndsInErrorAndGoesNoFurther() {
        claimable();

        worker(id -> {
                    throw new IllegalStateException("grading crashed");
                })
                .work(ID);

        verify(submissions).finish(ID, SubmissionStatus.ERROR, NOW_MICROS);
        verify(submissions, never()).finish(anyLong(), eq(SubmissionStatus.GRADED), any());
    }

    @Test
    void aCheckedExceptionThrownSneakilyAlsoEndsInError() {
        claimable();

        worker(id -> SubmissionWorkerTests.<RuntimeException>sneakyThrow(new IOException("disk gone")))
                .work(ID);

        verify(submissions).finish(ID, SubmissionStatus.ERROR, NOW_MICROS);
        verify(submissions, never()).finish(anyLong(), eq(SubmissionStatus.GRADED), any());
    }

    @Test
    void anErrorEndsInErrorAndIsThenRethrown() {
        claimable();
        StackOverflowError overflow = new StackOverflowError();

        assertThatThrownBy(() -> worker(id -> {
                            throw overflow;
                        })
                        .work(ID))
                .isSameAs(overflow);

        verify(submissions).finish(ID, SubmissionStatus.ERROR, NOW_MICROS);
        verify(submissions, never()).finish(anyLong(), eq(SubmissionStatus.GRADED), any());
    }

    @Test
    void aSubmissionSomeoneElseHasIsNeitherProcessedNorFinished() {
        when(submissions.claim(anyLong(), any())).thenReturn(0);

        worker(calls::add).work(ID);

        assertThat(calls).isEmpty();
        verify(submissions, never()).finish(anyLong(), any(), any());
    }

    @Test
    void withoutAProcessorAClaimedSubmissionStaysRunning() {
        claimable();

        worker(null).work(ID);

        verify(submissions).claim(ID, NOW_MICROS);
        verify(submissions, never()).finish(anyLong(), any(), any());
    }
}
