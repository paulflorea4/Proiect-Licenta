package com.gradingplatform.backend.sandbox;

import java.util.Objects;

/**
 * The default {@link OutputComparison} (4.5b): both texts are normalised and must then be equal.
 *
 * <p>Normalising a text means:
 *
 * <ol>
 *   <li>split it into lines at {@code \n};
 *   <li>strip <b>trailing</b> whitespace from every line ({@link Character#isWhitespace(char)}: space,
 *       tab, form feed, vertical tab and so on, and the {@code \r} of a Windows line ending, so CRLF and LF
 *       texts are equal);
 *   <li>drop the empty lines at the end, so any number of trailing newlines, or none, are the same.
 * </ol>
 *
 * <p>Everything else is significant: leading whitespace, blank lines in the middle, the case and
 * every character of the text. Two programs that print {@code "1 2"} and {@code "1  2"} differ, and so
 * do {@code " 1"} and {@code "1"}. A lone {@code \r} inside a line (not before a {@code \n}) is an
 * ordinary character and is kept. Characters that {@code isWhitespace} excludes on purpose, such as the
 * no-break space, are visible differences and also count.
 *
 * <p>Empty output and output of only whitespace and newlines normalise to the same empty text, so they
 * match each other; they match nothing else.
 */
public final class TrailingWhitespaceComparison implements OutputComparison {

    /** Stateless, so one instance serves everyone. */
    public static final TrailingWhitespaceComparison INSTANCE = new TrailingWhitespaceComparison();

    private TrailingWhitespaceComparison() {}

    @Override
    public boolean matches(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    /** The text as described on the class: lines without trailing whitespace, no trailing empty lines. */
    static String normalize(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder(text.length());
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? text.length() : newline;
            int end = lineEnd;
            while (end > lineStart && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            out.append(text, lineStart, end);
            if (newline < 0) {
                break;
            }
            out.append('\n');
            lineStart = newline + 1;
        }
        int length = out.length();
        while (length > 0 && out.charAt(length - 1) == '\n') {
            length--;
        }
        out.setLength(length);
        return out.toString();
    }
}
