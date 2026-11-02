package com.gradingplatform.backend;

import com.gradingplatform.backend.security.AuthCookie;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Reads the token out of a signin's {@code Set-Cookie} header (8.2b: the token is no longer in the
 * body), for tests that sign in and then call with {@code Authorization: Bearer}.
 */
final class SigninCookies {

    private SigninCookies() {}

    static String tokenOf(HttpResponse<?> signin) {
        return tokenIn(signin.headers().allValues("Set-Cookie"));
    }

    static String tokenOf(MockHttpServletResponse signin) {
        return tokenIn(signin.getHeaders("Set-Cookie"));
    }

    /** The one {@code Set-Cookie} header for the auth cookie, attributes included. */
    static String headerOf(HttpResponse<?> signin) {
        return single(signin.headers().allValues("Set-Cookie"));
    }

    static String headerOf(MockHttpServletResponse signin) {
        return single(signin.getHeaders("Set-Cookie"));
    }

    private static String tokenIn(List<String> setCookies) {
        String header = single(setCookies);
        int end = header.indexOf(';');
        return header.substring(AuthCookie.NAME.length() + 1, end < 0 ? header.length() : end);
    }

    private static String single(List<String> setCookies) {
        List<String> auth = setCookies.stream()
                .filter(value -> value.startsWith(AuthCookie.NAME + "="))
                .toList();
        if (auth.size() != 1) {
            throw new AssertionError("expected one " + AuthCookie.NAME + " cookie, got " + setCookies);
        }
        return auth.get(0);
    }
}
