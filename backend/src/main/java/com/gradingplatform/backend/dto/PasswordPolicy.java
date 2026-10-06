package com.gradingplatform.backend.dto;

/**
 * What a signup password must satisfy. Length only: no composition rules (digits, symbols), which
 * add friction without making passwords much stronger.
 */
public final class PasswordPolicy {

    /** Placeholder pending the human's decision; kept as a named constant, not inlined. */
    public static final int MIN_LENGTH = 8;

    /**
     * BCrypt only uses the first 72 bytes of a password and Spring's encoder refuses longer ones,
     * so this is a limit in bytes (UTF-8), not characters.
     */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {}
}
