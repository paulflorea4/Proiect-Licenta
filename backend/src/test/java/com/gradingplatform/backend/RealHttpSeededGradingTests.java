package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.sandbox.SandboxProperties;
import com.gradingplatform.backend.sandbox.SandboxTestImages;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 5.6b: the whole path on the seeded course (1.4b), as the people of the dev seed would take it:
 * sign in over HTTP with the documented dev logins, submit to the seeded Java and Python
 * assignments, wait for the worker to grade them in real sandbox containers (Docker), then read
 * the result as the student and as the teacher. Every expected score is worked out by hand from
 * the seeded rubrics (V1001), never taken from a run:
 *
 * <ul>
 *   <li>Sum of Two Numbers (Java): Correctness 80 (TESTS) over five tests of weights 1, 1, 1, 1
 *       and 2 (total 6, the last is the overflow test); Code quality 20 (MANUAL).
 *   <li>Palindrome Check (Python, 3 attempts): Basic cases 40 over three tests of weight 1; Edge
 *       cases 40 over two of weight 1; Readability 20 (MANUAL).
 * </ul>
 *
 * A MANUAL criterion nobody has scored is pending: no score row, and the grade is provisional.
 *
 * <p>This class does not extend {@link RealHttpTestBase}: that empties the tables around every
 * test, and here the seed is the world. The seeded submissions (V1002) stay, so a test never
 * assumes an attempt number, and each Python student has exactly one attempt left, used by one
 * test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.locations=classpath:db/migration,classpath:db/dev-seed")
class RealHttpSeededGradingTests {

    private static final String STUDENT_1 = "student1@dev.example.com";
    private static final String STUDENT_2 = "student2@dev.example.com";

    private static final String JAVA_LONG_SUM = "import java.util.Scanner;\n\npublic class Main {\n"
            + "    public static void main(String[] args) {\n"
            + "        Scanner in = new Scanner(System.in);\n"
            + "        long a = in.nextLong();\n        long b = in.nextLong();\n"
            + "        System.out.println(a + b);\n    }\n}\n";
    /** The classic mistake the seeded overflow test is there for: the sum is computed in an int. */
    private static final String JAVA_INT_SUM = JAVA_LONG_SUM
            .replace("long a = in.nextLong()", "int a = in.nextInt()")
            .replace("long b = in.nextLong()", "int b = in.nextInt()");

    private static final String JAVA_MISSING_SEMICOLON =
            JAVA_LONG_SUM.replace("nextLong();\n        long b", "nextLong()\n        long b");
    private static final String PYTHON_PALINDROME = "w = input()\nprint(\"YES\" if w == w[::-1] else \"NO\")\n";
    /** Ignores the case, which the seeded "Case-sensitive" test (Madam is not a palindrome) catches. */
    private static final String PYTHON_PALINDROME_IGNORING_CASE =
            "w = input().lower()\nprint(\"YES\" if w == w[::-1] else \"NO\")\n";

    @LocalServerPort
    private int port;

    @Autowired
    private SandboxProperties languages;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void images() {
        SandboxTestImages.ensureBuilt(languages, "JAVA");
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
    }

    // ---- the correct Java solution ----

    @Test
    void aCorrectJavaSolutionPassesEveryTestAndWaitsOnlyForTheTeachersScore() throws Exception {
        String student = signIn(STUDENT_2, "Student-dev-2");
        long id = submit(student, assignmentId("Sum of Two Numbers"), JAVA_LONG_SUM);

        String body = waitForGrading(student, id);

        assertThat(string(body, "$.language")).isEqualTo("JAVA");
        assertThat(string(body, "$.sourceCode")).isEqualTo(JAVA_LONG_SUM);
        assertThat(JsonPath.<Object>read(body, "$.startedAt")).isNotNull();
        assertThat(JsonPath.<Object>read(body, "$.finishedAt")).isNotNull();
        assertThat(JsonPath.<Object>read(body, "$.compilerOutput")).isNull();
        // All five tests passed, test weights 1+1+1+1+2: 80 × 6/6 = 80.00. Code quality is pending:
        // total 80.00 of 100.00, and the grade is provisional.
        assertGrade(body, "80.00", "100.00", true);
        assertThat(criteria(body))
                .containsExactly(Map.entry("Correctness", "80.00"), Map.entry("Code quality", "null"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from criterion_scores where submission_id = ?", Integer.class, id))
                .as("only the TESTS criterion has a row; the pending one has none")
                .isEqualTo(1);

        // What the student sees: two public tests in full, three hidden ones as pass/fail only.
        List<Map<String, Object>> tests = tests(body);
        assertThat(tests).hasSize(5);
        assertThat(tests.get(0))
                .containsEntry("name", "Small numbers")
                .containsEntry("status", "PASSED")
                .containsEntry("actualOutput", "3\n");
        assertThat(tests.get(1)).containsEntry("name", "Negative number").containsEntry("status", "PASSED");
        for (int i = 2; i < 5; i++) {
            assertThat(tests.get(i).keySet())
                    .as("hidden test %d is pass/fail only", i + 1)
                    .containsExactlyInAnyOrder("id", "name", "visibility", "position", "passed");
            assertThat(tests.get(i))
                    .containsEntry("name", "Hidden test " + (i - 1))
                    .containsEntry("passed", true);
        }
        assertThat(body).doesNotContain("4000000000");

        // What the teacher sees: the same results with the hidden tests in full.
        String teacher = signIn("teacher@dev.example.com", "Teacher-dev-1");
        String teacherBody = fetch(teacher, id);
        assertGrade(teacherBody, "80.00", "100.00", true);
        List<Map<String, Object>> teacherTests = tests(teacherBody);
        assertThat(teacherTests).extracting(t -> t.get("status")).containsOnly("PASSED");
        assertThat(teacherTests.get(4))
                .containsEntry("name", "Overflows an int")
                .containsEntry("input", "2000000000 2000000000\n")
                .containsEntry("expectedOutput", "4000000000")
                .containsEntry("actualOutput", "4000000000\n");
    }

    // ---- partial credit by weight ----

    @Test
    void anIntOverflowFailsOnlyTheHeavyTestAndCostsItsWeight() throws Exception {
        String student = signIn(STUDENT_1, "Student-dev-1");
        long id = submit(student, assignmentId("Sum of Two Numbers"), JAVA_INT_SUM);

        String body = waitForGrading(student, id);

        // The overflow test (weight 2) fails, the other four (weight 4) pass: 80 × 4/6 = 53.333... =
        // 53.33. Code quality is pending.
        assertGrade(body, "53.33", "100.00", true);
        List<Map<String, Object>> tests = tests(body);
        assertThat(tests.get(0)).containsEntry("status", "PASSED");
        assertThat(tests.get(1)).containsEntry("status", "PASSED");
        assertThat(tests.get(2)).containsEntry("passed", true);
        assertThat(tests.get(3)).containsEntry("passed", true);
        assertThat(tests.get(4)).containsEntry("passed", false);

        String teacherBody = fetch(signIn("teacher@dev.example.com", "Teacher-dev-1"), id);
        assertThat(tests(teacherBody).get(4))
                .containsEntry("status", "FAILED")
                .containsEntry("actualOutput", "-294967296\n");
    }

    @Test
    void javaThatDoesNotCompileIsGradedZeroWithTheCompilersMessage() throws Exception {
        String student = signIn(STUDENT_2, "Student-dev-2");
        long id = submit(student, assignmentId("Sum of Two Numbers"), JAVA_MISSING_SEMICOLON);

        String body = waitForGrading(student, id);

        // Nothing could run, so no test passed: 0.00 of 100.00, graded (not an error), provisional.
        assertGrade(body, "0.00", "100.00", true);
        assertThat(string(body, "$.compilerOutput")).contains("Main.java").contains("error");
        List<Map<String, Object>> tests = tests(body);
        assertThat(tests).hasSize(5);
        assertThat(tests.get(0)).containsEntry("status", "COMPILE_ERROR");
        assertThat(tests.get(0).get("actualOutput")).isNull();
        for (int i = 2; i < 5; i++) {
            assertThat(tests.get(i)).containsEntry("passed", false);
        }
    }

    // ---- two TESTS criteria in Python, and the attempt limit ----

    @Test
    void eachTestsCriterionIsScoredOnItsOwnAndTheLastAttemptIsTheLastAllowed() throws Exception {
        String student = signIn(STUDENT_1, "Student-dev-1");
        long assignment = assignmentId("Palindrome Check");
        long id = submit(student, assignment, PYTHON_PALINDROME_IGNORING_CASE);

        String body = waitForGrading(student, id);

        // Basic cases: level, hello, abba all right = 40 × 3/3 = 40.00. Edge cases: "a" right, "Madam"
        // wrong (lower-cased it reads as a palindrome) = 40 × 1/2 = 20.00. Readability is pending.
        // Total 40.00 + 20.00 = 60.00 of 100.00.
        assertGrade(body, "60.00", "100.00", true);
        assertThat(criteria(body))
                .containsExactly(
                        Map.entry("Basic cases", "40.00"),
                        Map.entry("Edge cases", "20.00"),
                        Map.entry("Readability", "null"));
        List<Map<String, Object>> tests = tests(body);
        assertThat(tests).hasSize(5);
        assertThat(tests.get(0)).containsEntry("name", "Palindrome").containsEntry("status", "PASSED");
        assertThat(tests.get(1)).containsEntry("name", "Not a palindrome").containsEntry("status", "PASSED");
        assertThat(tests.get(2)).containsEntry("passed", true);
        assertThat(tests.get(3)).containsEntry("passed", true);
        assertThat(tests.get(4)).containsEntry("passed", false);

        // The student had two seeded attempts and this was the third: a fourth is refused.
        HttpResponse<String> fourth = send(
                "POST",
                "/assignments/" + assignment + "/submissions",
                student,
                "{\"sourceCode\":" + quoted(PYTHON_PALINDROME) + "}");
        assertThat(fourth.statusCode()).as(fourth.body()).isEqualTo(409);
        assertThat(string(fourth.body(), "$.code")).isEqualTo("ATTEMPT_LIMIT_REACHED");
    }

    @Test
    void aCorrectPythonSolutionScoresBothTestsCriteriaInFull() throws Exception {
        String student = signIn(STUDENT_2, "Student-dev-2");
        long id = submit(student, assignmentId("Palindrome Check"), PYTHON_PALINDROME);

        String body = waitForGrading(student, id);

        // Basic 40 × 3/3 = 40.00 and Edge 40 × 2/2 = 40.00, Readability pending: 80.00 of 100.00.
        assertGrade(body, "80.00", "100.00", true);
        assertThat(criteria(body))
                .containsExactly(
                        Map.entry("Basic cases", "40.00"),
                        Map.entry("Edge cases", "40.00"),
                        Map.entry("Readability", "null"));
        assertThat(tests(body)).allSatisfy(t -> {
            if ("PUBLIC".equals(t.get("visibility"))) {
                assertThat(t).containsEntry("status", "PASSED");
            } else {
                assertThat(t).containsEntry("passed", true);
            }
        });
        assertThat(jdbc.queryForObject(
                        "select count(*) from criterion_scores where submission_id = ?", Integer.class, id))
                .as("two TESTS criteria have a row each, Readability has none")
                .isEqualTo(2);
    }

    // ---- helpers ----

    private HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(
                        method,
                        json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Signs in with a documented dev login (header of V1000) and returns the token. */
    private String signIn(String email, String password) throws Exception {
        HttpResponse<String> response =
                send("POST", "/auth/signin", null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return string(response.body(), "$.token");
    }

    private long assignmentId(String title) {
        return jdbc.queryForObject("select id from assignments where title = ?", Long.class, title);
    }

    /** Submits as text and returns the new submission's id. */
    private long submit(String token, long assignmentId, String code) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/assignments/" + assignmentId + "/submissions",
                token,
                "{\"sourceCode\":" + quoted(code) + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        assertThat(string(response.body(), "$.status")).isEqualTo("QUEUED");
        return JsonPath.<Number>read(response.body(), "$.id").longValue();
    }

    /** The code as a JSON string; these programs hold only backslashes, quotes and line breaks. */
    private static String quoted(String code) {
        return "\"" + code.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String fetch(String token, long submissionId) throws Exception {
        HttpResponse<String> response = send("GET", "/submissions/" + submissionId, token, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    /** Polls the student's own view until the submission is {@code GRADED}; any other end fails. */
    private String waitForGrading(String studentToken, long submissionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (true) {
            String body = fetch(studentToken, submissionId);
            String status = string(body, "$.status");
            if (status.equals("GRADED")) {
                return body;
            }
            assertThat(status).as("still being graded, or failed").isIn("QUEUED", "RUNNING");
            assertThat(System.nanoTime()).as("graded within three minutes").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private static String string(String body, String path) {
        return JsonPath.read(body, path);
    }

    private static void assertGrade(String body, String total, String max, boolean provisional) {
        assertThat(BigDecimal.valueOf(
                        JsonPath.<Number>read(body, "$.grade.totalScore").doubleValue()))
                .as("total score")
                .isEqualByComparingTo(total);
        assertThat(BigDecimal.valueOf(
                        JsonPath.<Number>read(body, "$.grade.maxScore").doubleValue()))
                .as("max score")
                .isEqualByComparingTo(max);
        assertThat(JsonPath.<Boolean>read(body, "$.grade.provisional"))
                .as("provisional")
                .isEqualTo(provisional);
        assertThat(string(body, "$.status")).isEqualTo("GRADED");
    }

    /** Criterion name to its score as text ("null" while pending), in rubric order. */
    private static List<Map.Entry<String, String>> criteria(String body) {
        List<Map<String, Object>> rows = JsonPath.read(body, "$.criteria");
        Map<String, String> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object score = row.get("score");
            byName.put(
                    (String) row.get("name"),
                    score == null
                            ? "null"
                            : new BigDecimal(String.valueOf(score)).setScale(2).toPlainString());
        }
        return List.copyOf(byName.entrySet());
    }

    private static List<Map<String, Object>> tests(String body) {
        return JsonPath.read(body, "$.tests");
    }
}
