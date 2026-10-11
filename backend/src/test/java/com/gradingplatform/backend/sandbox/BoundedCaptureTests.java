package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 4.3c: keeping only the first bytes of a stream, and saying so, without Docker. */
class BoundedCaptureTests {

    private static BoundedCapture capture(byte[] bytes, int limit) {
        BoundedCapture capture = new BoundedCapture(new ByteArrayInputStream(bytes), limit);
        capture.run();
        return capture;
    }

    private static BoundedCapture capture(String text, int limit) {
        return capture(text.getBytes(StandardCharsets.UTF_8), limit);
    }

    @Test
    void outputBelowTheLimitIsKeptWhole() {
        BoundedCapture capture = capture("hello\n", 100);

        assertThat(capture.text()).isEqualTo("hello\n");
        assertThat(capture.truncated()).isFalse();
    }

    @Test
    void emptyOutputIsEmptyAndNotTruncated() {
        BoundedCapture capture = capture("", 10);

        assertThat(capture.text()).isEmpty();
        assertThat(capture.truncated()).isFalse();
    }

    @Test
    void outputOfExactlyTheLimitIsKeptWholeAndNotMarkedTruncated() {
        BoundedCapture capture = capture("a".repeat(10), 10);

        assertThat(capture.text()).isEqualTo("a".repeat(10));
        assertThat(capture.truncated()).isFalse();
    }

    @Test
    void oneByteOverTheLimitIsCutAndMarked() {
        BoundedCapture capture = capture("a".repeat(10) + "b", 10);

        assertThat(capture.text()).isEqualTo("a".repeat(10));
        assertThat(capture.truncated()).isTrue();
    }

    @Test
    void aHugeStreamIsReadToTheEndButOnlyTheBeginningIsKept() {
        AtomicLong delivered = new AtomicLong();
        long size = 200L * 1024 * 1024;
        InputStream endless = new InputStream() {
            long left = size;

            @Override
            public int read() {
                return read(new byte[1], 0, 1) == -1 ? -1 : 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left == 0) {
                    return -1;
                }
                int n = (int) Math.min(len, left);
                java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                left -= n;
                delivered.addAndGet(n);
                return n;
            }
        };
        BoundedCapture capture = new BoundedCapture(endless, 1024);

        capture.run();

        // Everything was consumed (so a writer is never blocked), yet only 1 KiB is held.
        assertThat(delivered.get()).isEqualTo(size);
        assertThat(capture.text()).hasSize(1024).matches("x+");
        assertThat(capture.truncated()).isTrue();
    }

    @Test
    void theContentIsTheBeginningOfTheStreamInOrderWhatEverTheChunking() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            text.append(i).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        InputStream oneByteAtATime = new InputStream() {
            int at;

            @Override
            public int read() {
                return at < bytes.length ? bytes[at++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                int value = read();
                if (value == -1) {
                    return -1;
                }
                b[off] = (byte) value;
                return 1;
            }
        };
        BoundedCapture capture = new BoundedCapture(oneByteAtATime, 1000);

        capture.run();

        assertThat(capture.text()).isEqualTo(text.substring(0, 1000));
        assertThat(capture.truncated()).isTrue();
    }

    // --- the cut never splits a character -------------------------------------------------------

    @Test
    void aCutInsideATwoByteCharacterDropsThatCharacter() {
        // a(1) é(2) €(3): 6 bytes. Limit 2 ends in the middle of é.
        BoundedCapture capture = capture("aé€", 2);

        assertThat(capture.text()).isEqualTo("a");
        assertThat(capture.truncated()).isTrue();
    }

    @Test
    void aCutInsideAThreeByteCharacterDropsThatCharacter() {
        assertThat(capture("aé€", 4).text()).isEqualTo("aé");
        assertThat(capture("aé€", 5).text()).isEqualTo("aé");
    }

    @Test
    void aCutInsideAFourByteCharacterDropsThatCharacter() {
        // "a" + U+1F600 (4 bytes)
        assertThat(capture("a😀b", 2).text()).isEqualTo("a");
        assertThat(capture("a😀b", 4).text()).isEqualTo("a");
        assertThat(capture("a😀b", 5).text()).isEqualTo("a😀");
    }

    @Test
    void aCutExactlyBetweenCharactersKeepsThemAll() {
        BoundedCapture capture = capture("aé€", 3);

        assertThat(capture.text()).isEqualTo("aé");
        assertThat(capture.truncated()).isTrue();
    }

    @Test
    void thereIsNeverAReplacementCharacterThatTheProgramDidNotPrint() {
        String text = "é".repeat(1_000);
        for (int limit = 1; limit < 40; limit++) {
            assertThat(capture(text, limit).text()).as("limit " + limit).doesNotContain("�");
        }
    }

    // --- failure and misuse ---------------------------------------------------------------------

    @Test
    void whenTheStreamBreaksWhatWasReadSoFarStands() {
        InputStream breaking = new InputStream() {
            int sent;

            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (sent++ == 0) {
                    b[off] = 'o';
                    b[off + 1] = 'k';
                    return 2;
                }
                throw new IOException("closed");
            }
        };
        BoundedCapture capture = new BoundedCapture(breaking, 100);

        capture.run();

        assertThat(capture.text()).isEqualTo("ok");
        assertThat(capture.truncated()).isFalse();
    }

    @Test
    void theLimitMustBePositiveAndFitAnArray() {
        InputStream none = new ByteArrayInputStream(new byte[0]);

        assertThatThrownBy(() -> new BoundedCapture(none, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedCapture(none, -5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedCapture(none, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
