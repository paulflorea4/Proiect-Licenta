package com.gradingplatform.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.dto.TestCaseRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.security.RequestBodyLimitFilter;
import com.gradingplatform.backend.service.SubmissionService;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 5.1f: a JSON body over the server's maximum ({@link RequestBodyLimitFilter#MAX_BODY_BYTES}) is refused with 413 `REQUEST_TOO_LARGE`
 * before anything reads it, whoever sends it, with a declared length or chunked; the largest valid
 * bodies still pass, even with every character escaped.
 */
class RealHttpRequestBodyLimitTests extends RealHttpTestBase {

    private static final int MAX = RequestBodyLimitFilter.MAX_BODY_BYTES;

    private User owner;
    private User student;
    private Assignment draft;
    private Assignment published;
    private RubricCriterion criterion;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        draft = assignments.save(assignment(course));
        criterion = rubricCriteria.save(new RubricCriterion(draft.getId(), "Tests", CriterionType.TESTS, 100));
        published = assignments.save(assignment(course));
        jdbc.update("update assignments set published = true where id = ?", published.getId());
    }

    @AfterEach
    void removeTests() {
        jdbc.update("delete from test_cases");
    }

    private static Assignment assignment(Course course) {
        return new Assignment(
                course.getId(),
                "Sorting",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null);
    }

    private String submissionsPath() {
        return "/assignments/" + published.getId() + "/submissions";
    }

    /** Sends a JSON body as given. */
    private HttpResponse<String> send(String path, BodyPublisher body, User as) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(body)
                .header("Content-Type", "application/json");
        if (as != null) {
            request.header("Authorization", "Bearer " + tokenFor(as));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Declares a JSON body of {@code declared} bytes but sends only the headers, then reads the
     * answer (framed by its {@code Content-Length}). An answer at all proves the body was not read:
     * not a byte of it was ever sent.
     */
    private Reply declareOnly(String path, long declared, User as) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + declared + "\r\n"
                    + (as == null ? "" : "Authorization: Bearer " + tokenFor(as) + "\r\n")
                    + "\r\n";
            socket.getOutputStream().write(head.getBytes(UTF_8));
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            while (!headBytes.toString(UTF_8).endsWith("\r\n\r\n")) {
                int b = in.read();
                assertThat(b)
                        .as("the server closed before the end of its headers")
                        .isNotNegative();
                headBytes.write(b);
            }
            String[] lines = headBytes.toString(UTF_8).strip().split("\r\n");
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String[] header = lines[i].split(":", 2);
                headers.put(header[0].trim().toLowerCase(Locale.ROOT), header[1].trim());
            }
            assertThat(headers).containsKey("content-length");
            byte[] body = in.readNBytes(Integer.parseInt(headers.get("content-length")));
            return new Reply(Integer.parseInt(lines[0].split(" ")[1]), headers, new String(body, UTF_8));
        }
    }

    /** What a refusal is checked on, from either client. */
    private record Reply(int status, Map<String, String> headers, String body) {

        static Reply of(HttpResponse<String> response) {
            Map<String, String> headers = new HashMap<>();
            response.headers()
                    .map()
                    .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
            return new Reply(response.statusCode(), headers, response.body());
        }
    }

    /** No declared length: the client sends the body chunked. */
    private static BodyPublisher chunked(String json) {
        byte[] bytes = json.getBytes(UTF_8);
        BodyPublisher publisher = BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
        assertThat(publisher.contentLength()).isNegative();
        return publisher;
    }

    /** A source-code body of exactly {@code size} bytes. */
    private static String submissionOfSize(int size) {
        String prefix = "{\"sourceCode\":\"";
        String suffix = "\"}";
        return prefix + "#".repeat(size - prefix.length() - suffix.length()) + suffix;
    }

    /** Every character as a JSON {@code \}{@code uXXXX} escape: 6 bytes each, the most a client may use. */
    private static String escaped(String text) {
        StringBuilder json = new StringBuilder("\"");
        text.chars().forEach(c -> json.append(String.format("\\u%04x", c)));
        return json.append('"').toString();
    }

    private static void assertTooLarge(HttpResponse<String> response) {
        assertTooLarge(Reply.of(response));
    }

    private static void assertTooLarge(Reply reply) {
        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.headers()).containsEntry("connection", "close");
        assertThat(reply.headers().get("content-type")).startsWith("application/json");
        assertThat(JsonPath.<Map<String, Object>>read(reply.body(), "$"))
                .containsEntry("status", 413)
                .containsEntry("code", "REQUEST_TOO_LARGE")
                .containsOnlyKeys("status", "code", "message");
    }

    @Test
    void aDeclaredBodyOverTheMaximumIsRefusedAndNothingIsStored() throws Exception {
        assertTooLarge(declareOnly(submissionsPath(), MAX + 1L, student));
        assertTooLarge(declareOnly(submissionsPath(), 100L * 1024 * 1024 * 1024, student));
        assertThat(submissions.count()).isZero();
    }

    @Test
    void aChunkedBodyOverTheMaximumIsRefusedAndNothingIsStored() throws Exception {
        assertTooLarge(send(submissionsPath(), chunked(submissionOfSize(MAX + 1)), student));
        assertThat(submissions.count()).isZero();
    }

    @Test
    void theBoundComesBeforeAuthenticationAndTheRoleRule() throws Exception {
        // Anonymous on a protected endpoint would be 401, a teacher 403: the size is judged first.
        assertTooLarge(declareOnly(submissionsPath(), MAX + 1L, null));
        assertTooLarge(declareOnly(submissionsPath(), MAX + 1L, owner));
        assertTooLarge(send("/auth/signup", chunked(submissionOfSize(MAX + 1)), null));
        assertThat(submissions.count()).isZero();
    }

    @Test
    void aChunkedBodyWithinTheMaximumReachesTheControllerIntact() throws Exception {
        String code = "print('é ✓')\n";

        HttpResponse<String> response =
                send(submissionsPath(), chunked("{\"sourceCode\":\"print('é ✓')\\n\"}"), student);

        assertThat(response.statusCode()).isEqualTo(202);
        long id = JsonPath.<Number>read(response.body(), "$.id").longValue();
        assertThat(submissions.findById(id).orElseThrow().getSourceCode()).isEqualTo(code);
    }

    @Test
    void aBodyJustUnderTheMaximumIsNotRefusedByTheBound() throws Exception {
        // Over the source cap, so the controller answers; what matters is that it is not REQUEST_TOO_LARGE.
        for (BodyPublisher body :
                new BodyPublisher[] {BodyPublishers.ofString(submissionOfSize(MAX)), chunked(submissionOfSize(MAX))}) {
            HttpResponse<String> response = send(submissionsPath(), body, student);

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("SOURCE_TOO_LARGE");
        }
    }

    @Test
    void theLargestValidTestCaseIsAcceptedWithEveryCharacterEscaped() throws Exception {
        String name = "n".repeat(TestCaseRequest.NAME_MAX);
        String input = "i".repeat(TestCaseRequest.TEXT_MAX);
        String expected = "o".repeat(TestCaseRequest.TEXT_MAX);
        String json = "{\"criterionId\":" + criterion.getId()
                + ",\"name\":" + escaped(name)
                + ",\"input\":" + escaped(input)
                + ",\"expectedOutput\":" + escaped(expected)
                + ",\"visibility\":\"HIDDEN\",\"weight\":100,\"position\":100000}";
        assertThat(json.getBytes(UTF_8).length).isGreaterThan(1_200_000).isLessThan(MAX);

        HttpResponse<String> response =
                send("/assignments/" + draft.getId() + "/tests", BodyPublishers.ofString(json), owner);

        assertThat(response.statusCode()).isEqualTo(201);
        TestCase stored = testCases.findAll().get(0);
        assertThat(stored.getName()).isEqualTo(name);
        assertThat(stored.getInput()).isEqualTo(input);
        assertThat(stored.getExpectedOutput()).isEqualTo(expected);
    }

    @Test
    void theLargestValidSubmissionIsAcceptedWithEveryCharacterEscaped() throws Exception {
        String code = "#".repeat(SubmissionService.SOURCE_MAX_BYTES);

        HttpResponse<String> response =
                send(submissionsPath(), BodyPublishers.ofString("{\"sourceCode\":" + escaped(code) + "}"), student);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(submissions.findAll().get(0).getSourceCode()).isEqualTo(code);
    }
}
