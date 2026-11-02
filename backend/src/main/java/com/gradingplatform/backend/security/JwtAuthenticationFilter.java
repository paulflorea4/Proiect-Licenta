package com.gradingplatform.backend.security;

import com.gradingplatform.backend.dto.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the token from `Authorization: Bearer <token>` or, when no `Authorization` header is sent,
 * from the {@link AuthCookie} (8.2c), and, when it verifies, authenticates the request with the
 * user and role from its claims. A missing, malformed, forged or expired token simply leaves the
 * request anonymous: the access rules then answer 401 on a protected path and let a public one
 * through. The filter rejects only one thing itself: a state-changing request authenticated by the
 * cookie without the {@link AuthCookie#CSRF_HEADER} (8.2d), 403 `CSRF_HEADER_MISSING`.
 *
 * <p>Deliberately not a `@Component`: Spring Boot would also register a bean filter in the
 * servlet container, outside the security chain. {@link SecurityConfig} adds the one instance.
 */
class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** The methods that change nothing (RFC 9110 "safe"); every other one needs the CSRF header. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final JwtService jwtService;
    private final JsonErrorWriter writer;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    JwtAuthenticationFilter(JwtService jwtService, JsonErrorWriter writer) {
        this.jwtService = jwtService;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = tokenOf(request);
        Optional<AuthenticatedUser> user = token == null ? Optional.empty() : jwtService.parse(token);
        if (user.isPresent()) {
            // tokenOf reads the cookie only when no Authorization header is sent.
            boolean byCookie = request.getHeader(HttpHeaders.AUTHORIZATION) == null;
            if (byCookie && !SAFE_METHODS.contains(request.getMethod()) && !hasCsrfHeader(request)) {
                // CSRF (8.2d): the browser attaches the cookie by itself, so a write it carries must
                // also prove it was sent by script allowed past CORS. The only refusal this filter makes.
                writer.write(response, ErrorCode.CSRF_HEADER_MISSING);
                return;
            }
            authenticate(user.get());
        }
        chain.doFilter(request, response);
    }

    private static boolean hasCsrfHeader(HttpServletRequest request) {
        String value = request.getHeader(AuthCookie.CSRF_HEADER);
        return value != null && !value.isBlank();
    }

    /**
     * The token this request carries, or null. **An `Authorization` header, of any scheme, decides
     * alone**: the caller chose header authentication, so a bearer token is read from it and the
     * cookie is never looked at, even when the header's token is bad (no silent fallback to a
     * second credential). Without the header, the auth cookie is read; two cookies of that name (one
     * planted by another host under a parent domain, say) are ambiguous and give none.
     */
    static String tokenOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            return header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                    ? header.substring(BEARER_PREFIX.length()).trim()
                    : null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String token = null;
        boolean found = false;
        for (Cookie cookie : cookies) {
            if (AuthCookie.NAME.equals(cookie.getName())) {
                if (found) {
                    return null;
                }
                found = true;
                token = cookie.getValue();
            }
        }
        return token;
    }

    private void authenticate(AuthenticatedUser user) {
        // The ROLE_ prefix is what `hasRole("TEACHER")` and friends expect in later phases.
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
    }
}
