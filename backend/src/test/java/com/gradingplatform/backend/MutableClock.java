package com.gradingplatform.backend;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock a test can stop at an exact instant (5.1c: a submission at the deadline, one microsecond
 * after). Until {@link #set} it is the system clock, so tokens and everything else behave as usual;
 * {@link #reset} goes back to it. Wire it with {@code @Import(MutableClock.Config.class)}, which makes
 * it the application's {@code Clock} for that test context.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    public void set(Instant instant) {
        fixed.set(instant);
    }

    public void reset() {
        fixed.set(null);
    }

    @Override
    public Instant instant() {
        Instant now = fixed.get();
        return now == null ? Instant.now() : now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the application only uses UTC");
    }

    /** Registers one {@link MutableClock} as the primary {@code Clock}. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
