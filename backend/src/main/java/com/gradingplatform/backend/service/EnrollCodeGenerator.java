package com.gradingplatform.backend.service;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Makes the code a student types to join a course. Short enough to read out loud, and free of
 * characters that look alike: no `0`/`O`, no `1`/`l`/`I`. Upper case only, so a lookup (3.2a)
 * upper-cases what the student typed and never has to care how it was capitalised.
 *
 * <p>The code is the only thing standing between a student and a course, so it comes from a
 * {@link SecureRandom}: with 32 symbols and 8 positions there are 2^40 codes, too many to guess.
 * Uniqueness is not checked here; the unique constraint on `courses.enroll_code` decides, and
 * {@link CourseService} generates again when it objects.
 */
@Component
public class EnrollCodeGenerator {

    /** Digits 2-9 and the capital letters except `I` and `O`: 32 symbols. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Must stay within the `enroll_code VARCHAR(20)` column of V2. */
    static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    /**
     * What a student typed, made comparable with a stored code: surrounding spaces dropped and
     * upper-cased (codes are generated in upper case only). Nothing else is guessed, so a code with
     * a wrong letter simply matches nothing.
     */
    public static String normalize(String typed) {
        return typed.trim().toUpperCase(Locale.ROOT);
    }

    public String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
