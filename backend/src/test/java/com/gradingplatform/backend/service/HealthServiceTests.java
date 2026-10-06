package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 2.2b: how `HealthService` reads the database, including every way it can be down. */
class HealthServiceTests {

    private static final Duration SHORT = Duration.ofMillis(300);

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);

    @Test
    void upWhenAConnectionIsObtainedAndValid() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);

        assertThat(new HealthService(dataSource, SHORT).isDatabaseUp()).isTrue();
    }

    @Test
    void downWhenTheConnectionReportsItselfInvalid() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(false);

        assertThat(new HealthService(dataSource, SHORT).isDatabaseUp()).isFalse();
    }

    @Test
    void downWhenNoConnectionCanBeObtained() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThat(new HealthService(dataSource, SHORT).isDatabaseUp()).isFalse();
    }

    @Test
    void downAndPromptWhenTheConnectionAttemptHangs() throws Exception {
        CountDownLatch neverReleased = new CountDownLatch(1);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            neverReleased.await();
            return connection;
        });

        long start = System.nanoTime();
        boolean up = new HealthService(dataSource, SHORT).isDatabaseUp();
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(up).isFalse();
        assertThat(took).isLessThan(Duration.ofSeconds(2));
        neverReleased.countDown();
    }

    @Test
    void theDefaultTimeoutIsShorterThanAnOrchestratorsUsualProbeTimeout() {
        assertThat(HealthService.CHECK_TIMEOUT).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
