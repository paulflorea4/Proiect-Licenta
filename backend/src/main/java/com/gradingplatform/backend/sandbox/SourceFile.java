package com.gradingplatform.backend.sandbox;

import java.util.Objects;

/**
 * One file placed in the program's working directory. The name decides where it is written, so it
 * must be a plain file name: no directory part, no {@code ..}, nothing a path could be built from.
 *
 * @param name a plain file name, at most {@value #MAX_NAME_LENGTH} characters
 * @param content the text of the file, written as UTF-8 and never altered
 */
public record SourceFile(String name, String content) {

    public static final int MAX_NAME_LENGTH = 255;

    public SourceFile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        if (!isPlainFileName(name)) {
            throw new IllegalArgumentException("not a plain file name");
        }
    }

    /** True for a name that stays inside the working directory on every operating system we run on. */
    static boolean isPlainFileName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.equals(".") || name.equals("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        // Windows ignores trailing dots and spaces, which would let "a." and "a" be the same file.
        char last = name.charAt(name.length() - 1);
        return last != '.' && last != ' ' && name.charAt(0) != ' ';
    }

    /** The file name is harmless; the content is student code and stays out of log lines. */
    @Override
    public String toString() {
        return "SourceFile[name=" + name + ", content=<" + content.length() + " chars>]";
    }
}
