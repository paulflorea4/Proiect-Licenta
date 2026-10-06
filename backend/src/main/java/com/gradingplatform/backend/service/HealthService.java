package com.gradingplatform.backend.service;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Checks that the database can actually be reached, within a bounded time. */
@Service
public class HealthService {

    /** How long a health check may take. The pool can otherwise wait 30 s for a connection. */
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final DataSource dataSource;
    private final Duration timeout;

    @Autowired
    public HealthService(DataSource dataSource) {
        this(dataSource, CHECK_TIMEOUT);
    }

    /** For tests, which cannot wait the full {@link #CHECK_TIMEOUT}. */
    HealthService(DataSource dataSource, Duration timeout) {
        this.dataSource = dataSource;
        this.timeout = timeout;
    }

    /** True if a connection was obtained and the database answered within {@link #CHECK_TIMEOUT}. */
    public boolean isDatabaseUp() {
        // Run on its own thread so a stuck connection attempt cannot hold the request past the
        // timeout; the thread is abandoned and ends when the driver or the pool gives up.
        CompletableFuture<Boolean> check = CompletableFuture.supplyAsync(this::databaseAnswers);
        try {
            return check.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            check.cancel(true);
            log.warn("Health check: database did not answer within {}", timeout);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Health check: database check failed: {}", e.getCause().toString());
            return false;
        }
    }

    private boolean databaseAnswers() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(Math.max(1, (int) timeout.toSeconds()));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
