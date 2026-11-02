package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.security.AuthCookie;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 8.2d: a state-changing request authenticated by the {@link AuthCookie} must carry the
 * {@link AuthCookie#CSRF_HEADER}, else 403 `CSRF_HEADER_MISSING` and nothing changes. A request
 * authenticated by the bearer header, a safe method and a request whose cookie does not verify are
 * not affected. Over real HTTP, so the refusal is what Tomcat sends.
 */
class RealHttpCsrfHeaderTests extends RealHttpTestBase {

    private static final String COURSE = "{\"title\":\"Algorithms\"}";

    // --- refused without the header --------------------------------------------------------

    @Test
    void aCookieAuthenticatedPostWithoutTheHeaderIsRefusedAndCreatesNothing() throws Exception {
        String teacher = tokenFor(saved("tom@example.com", Role.TEACHER));

        HttpResponse<String> response = request("POST", "/courses", COURSE, "Cookie", cookie(teacher));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("CSRF_HEADER_MISSING");
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(courses.count()).isZero();
    }

    @Test
    void putPatchAndDeleteAreRefusedTooAndChangeNothing() throws Exception {
        User tom = saved("tom@example.com", Role.TEACHER);
        String teacher = tokenFor(tom);
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        User ada = saved("ada@example.com", Role.STUDENT);
        long courseId = createCourse(teacher);

        HttpResponse<String> put =
                request("PUT", "/courses/" + courseId, "{\"title\":\"Renamed\"}", "Cookie", cookie(teacher));
        HttpResponse<String> patch = request(
                "PATCH", "/admin/users/" + ada.getId() + "/role", "{\"role\":\"TEACHER\"}", "Cookie", cookie(admin));
        HttpResponse<String> delete = request("DELETE", "/courses/" + courseId, null, "Cookie", cookie(teacher));

        for (HttpResponse<String> response : List.of(put, patch, delete)) {
            assertThat(response.statusCode()).as(response.request().method()).isEqualTo(403);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("CSRF_HEADER_MISSING");
        }
        Course course = courses.findById(courseId).orElseThrow();
        assertThat(course.getTitle()).isEqualTo("Algorithms");
        assertThat(users.findById(ada.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
    }

    @Test
    void aBlankHeaderDoesNotCount() throws Exception {
        String teacher = tokenFor(saved("tom@example.com", Role.TEACHER));

        HttpResponse<String> response =
                request("POST", "/courses", COURSE, "Cookie", cookie(teacher), AuthCookie.CSRF_HEADER, " ");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(courses.count()).isZero();
    }

    @Test
    void anUnmappedOrPublicWriteIsRefusedToo() throws Exception {
        // The rule is about the credential, not the endpoint: a cookie that authenticates makes
        // every write need the header, signin (login CSRF) included.
        String student = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> unmapped =
                request("POST", "/no-handler-is-ever-mapped-here", "{}", "Cookie", cookie(student));
        HttpResponse<String> signin = request(
                "POST",
                "/auth/signin",
                "{\"email\":\"ada@example.com\",\"password\":\"whatever1\"}",
                "Cookie",
                cookie(student));

        assertThat(unmapped.statusCode()).isEqualTo(403);
        assertThat(signin.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(signin.body(), "$.code")).isEqualTo("CSRF_HEADER_MISSING");
    }

    // --- allowed ---------------------------------------------------------------------------

    @Test
    void aCookieAuthenticatedWriteWithTheHeaderGoesThrough() throws Exception {
        String teacher = tokenFor(saved("tom@example.com", Role.TEACHER));

        HttpResponse<String> response =
                request("POST", "/courses", COURSE, "Cookie", cookie(teacher), AuthCookie.CSRF_HEADER, "fetch");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(courses.count()).isEqualTo(1);
    }

    @Test
    void theHeaderNameIsMatchedIgnoringCase() throws Exception {
        String teacher = tokenFor(saved("tom@example.com", Role.TEACHER));

        HttpResponse<String> response =
                request("POST", "/courses", COURSE, "Cookie", cookie(teacher), "x-requested-with", "XMLHttpRequest");

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void aSafeMethodNeedsNoHeader() throws Exception {
        String student = tokenFor(saved("ada@example.com", Role.STUDENT));

        assertThat(request("GET", "/auth/me", null, "Cookie", cookie(student)).statusCode())
                .isEqualTo(200);
        assertThat(request("HEAD", "/auth/me", null, "Cookie", cookie(student)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void aBearerAuthenticatedWriteNeedsNoHeader() throws Exception {
        // Another site cannot make a browser send an Authorization header, so there is nothing to forge.
        String teacher = tokenFor(saved("tom@example.com", Role.TEACHER));

        HttpResponse<String> response = post("/courses", COURSE, teacher);

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void theBearerHeaderDecidesEvenWithACookieBesideIt() throws Exception {
        String teacher = tokenFor(saved("tom@example.com", Role.TEACHER));

        HttpResponse<String> response =
                request("POST", "/courses", COURSE, "Authorization", "Bearer " + teacher, "Cookie", cookie(teacher));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void aCookieThatDoesNotVerifyIsJustAnonymous() throws Exception {
        // Nothing to protect: the request has no credential, so the usual 401 / public rules apply.
        HttpResponse<String> protectedWrite =
                request("POST", "/courses", COURSE, "Cookie", AuthCookie.NAME + "=not.a.jwt");
        HttpResponse<String> signup = request(
                "POST",
                "/auth/signup",
                "{\"email\":\"new@example.com\",\"password\":\"correct horse\",\"fullName\":\"New\"}",
                "Cookie",
                AuthCookie.NAME + "=not.a.jwt");

        assertThat(protectedWrite.statusCode()).isEqualTo(401);
        assertThat(signup.statusCode()).isEqualTo(201);
    }

    @Test
    void aWriteWithNoCredentialAtAllIsTheUsual401() throws Exception {
        assertThat(request("POST", "/courses", COURSE).statusCode()).isEqualTo(401);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String cookie(String token) {
        return AuthCookie.NAME + "=" + token;
    }

    private long createCourse(String teacherToken) throws Exception {
        HttpResponse<String> created = post("/courses", COURSE, teacherToken);
        assertThat(created.statusCode()).isEqualTo(201);
        return JsonPath.<Number>read(created.body(), "$.id").longValue();
    }

    /** A request with a JSON body (or none) and exactly the given header name/value pairs. */
    private HttpResponse<String> request(String method, String path, String json, String... headers)
            throws IOException, InterruptedException {
        List<String> all = new ArrayList<>(List.of(headers));
        if (json != null) {
            all.addAll(List.of("Content-Type", "application/json"));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(
                        method,
                        json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (!all.isEmpty()) {
            request.headers(all.toArray(String[]::new));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
