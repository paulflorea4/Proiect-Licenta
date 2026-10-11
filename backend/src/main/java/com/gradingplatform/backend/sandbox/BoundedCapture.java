package com.gradingplatform.backend.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads a stream to its end but keeps only the first {@code limit} bytes (4.3c). The rest is read and
 * thrown away: the writer is never blocked by a full pipe, and neither memory nor disk grows with
 * what a program prints. {@link #truncated()} says whether anything was dropped, so output that was
 * cut can never be mistaken for output that was complete.
 *
 * <p>The cut never splits a UTF-8 character: a character that would be cut in half is dropped
 * whole, so the text has no replacement characters that the program did not print.
 */
final class BoundedCapture implements Runnable {

    private final InputStream source;
    private final int limit;

    /** Holds up to {@code limit + 1} bytes (the extra one shows what follows the cut); grows on demand. */
    private byte[] data = new byte[256];

    private int stored;
    private long total;

    BoundedCapture(InputStream source, int limit) {
        if (limit < 1 || limit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("limit must be between 1 and Integer.MAX_VALUE - 1");
        }
        this.source = source;
        this.limit = limit;
    }

    @Override
    public void run() {
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = source.read(chunk)) != -1) {
                accept(chunk, read);
            }
        } catch (IOException ignored) {
            // The stream was closed under us (the process was destroyed): what was read stands.
        }
    }

    private synchronized void accept(byte[] chunk, int length) {
        total += length;
        int take = Math.min(length, limit + 1 - stored);
        if (take <= 0) {
            return;
        }
        if (stored + take > data.length) {
            data = Arrays.copyOf(data, Math.min(Math.max(stored + take, data.length * 2), limit + 1));
        }
        System.arraycopy(chunk, 0, data, stored, take);
        stored += take;
    }

    /** True when more than {@code limit} bytes were written to the stream. */
    synchronized boolean truncated() {
        return total > limit;
    }

    /** The captured text: at most {@code limit} bytes, ending on a character boundary when cut. */
    synchronized String text() {
        int end = Math.min(stored, limit);
        if (total > limit) {
            // data[end] is the first dropped byte; a continuation byte means the cut is inside a character.
            while (end > 0 && (data[end] & 0xC0) == 0x80) {
                end--;
            }
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }
}
