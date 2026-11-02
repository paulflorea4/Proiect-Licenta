package com.gradingplatform.backend.security;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * The cookie that carries the JWT to the browser (8.2b, the human's decision at 8.3b, replacing
 * 2.4a's "JSON body only"). Script can never read it ({@code HttpOnly}), so an XSS bug cannot
 * steal the token; it travels only over HTTPS ({@code Secure}; browsers treat {@code localhost} as
 * secure) and only with requests from our own site ({@code SameSite=Strict}). It lives as long as
 * the token itself.
 */
public final class AuthCookie {

    /** The cookie's name; the filter (8.2c) and signout (8.2e) use the same one. */
    public static final String NAME = "access_token";

    /**
     * The header a state-changing request authenticated by this cookie must carry (8.2d, CSRF), with
     * any non-blank value. A form or a plain link on another site cannot add a header, and script on
     * another origin can only after a CORS preflight, which refuses origins not on the allow-list.
     */
    public static final String CSRF_HEADER = "X-Requested-With";

    /** {@code Strict}: the browser never attaches it to a request started by another site. */
    public static final String SAME_SITE = "Strict";

    private AuthCookie() {}

    /** The {@code Set-Cookie} value for a freshly signed token valid for {@code validFor}. */
    public static ResponseCookie issue(String token, Duration validFor) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path("/")
                .maxAge(validFor)
                .build();
    }
}
