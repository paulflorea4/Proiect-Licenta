package com.gradingplatform.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 8.0f: the request cuts the deadline to microseconds, the precision of the column, so what the
 * server answers is what it stored.
 */
class AssignmentRequestTests {

    private static AssignmentRequest requestWith(Instant deadline) {
        return new AssignmentRequest("Title", "Description", "JAVA", deadline, null, 1000, 256, null);
    }

    @Test
    void nanosecondsBelowAMicrosecondAreCutNotRounded() {
        // Postgres would round ...999 up to the next microsecond.
        Instant sent = Instant.parse("2030-01-01T00:00:00.123456999Z");

        assertThat(requestWith(sent).deadline()).isEqualTo(Instant.parse("2030-01-01T00:00:00.123456Z"));
    }

    @Test
    void aDeadlineAlreadyInMicrosecondsIsUnchanged() {
        Instant sent = Instant.parse("2030-01-01T00:00:00.123456Z");

        assertThat(requestWith(sent).deadline()).isEqualTo(sent);
    }

    @Test
    void aMissingDeadlineStaysMissingForValidationToRefuse() {
        assertThat(requestWith(null).deadline()).isNull();
    }
}
