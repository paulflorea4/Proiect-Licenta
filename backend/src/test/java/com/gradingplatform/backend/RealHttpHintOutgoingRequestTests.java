package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gradingplatform.backend.ai.AiServiceClient;
import com.gradingplatform.backend.ai.AiServiceProperties;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionResultWriter;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 7.5a: what actually leaves the backend for the AI service when a student asks for a hint. The
 * real {@link AiServiceClient} sends the request, and Spring's {@link MockRestServiceServer} takes
 * the place of the network, so the test reads the exact JSON body written for the wire. Every
 * hidden test's name, input, expected output, actual output and stderr holds a marker; none may be
 * in the body, and each hidden test must arrive as {@code {"passed": ...}} and nothing else.
 */
@Import(RealHttpHintOutgoingRequestTests.MockAiService.class)
class RealHttpHintOutgoingRequestTests extends RealHttpTestBase {

    /** A Spring-test mock behind the real client: a builder bound to the mock server, then the client on it. */
    @TestConfiguration
    static class MockAiService {

        record Wire(RestClient.Builder builder, MockRestServiceServer server) {}

        @Bean
        Wire mockAiWire() {
            RestClient.Builder builder = RestClient.builder();
            return new Wire(builder, MockRestServiceServer.bindTo(builder).build());
        }

        @Bean
        @Primary
        AiServiceClient mockedAiServiceClient(Wire wire, AiServiceProperties properties) {
            return new AiServiceClient(wire.builder(), properties);
        }
    }

    /** One marker per hidden detail and per hidden test; the four hidden tests each carry five. */
    private static String marker(String what, int test) {
        return "HIDDEN-" + what + "-" + test + "-7b2e";
    }

    private static final String[] WHAT = {"NAME", "INPUT", "EXPECTED", "ACTUAL", "STDERR"};

    @Autowired
    private MockAiService.Wire wire;

    @Autowired
    private AiServiceProperties properties;

    @Autowired
    private SubmissionResultWriter writer;

    private final List<String> sent = new ArrayList<>();

    private User owner;
    private User student;
    private Assignment assignment;
    private List<TestCase> tests;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "WIRE2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                "Read two integers and print their sum.",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
        long criterion = rubricCriteria
                .save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 100))
                .getId();
        // Run order: hidden 1, public "adds two", hidden 2, hidden 3, public "negatives", hidden 4.
        tests = List.of(
                hidden(criterion, 1, 1),
                testCases.save(new TestCase(
                        assignment.getId(), criterion, "adds two", "1 2", "3", TestVisibility.PUBLIC, 1, 2)),
                hidden(criterion, 2, 3),
                hidden(criterion, 3, 4),
                testCases.save(new TestCase(
                        assignment.getId(), criterion, "negatives", "-1 -2", "-3", TestVisibility.PUBLIC, 1, 5)),
                hidden(criterion, 4, 6));
        wire.server().reset();
        sent.clear();
    }

    @AfterEach
    void noHintLeftBehind() {
        jdbc.update("delete from ai_feedback");
    }

    @Test
    void eachHiddenTestLeavesAsOnlyWhetherItPassed() throws Exception {
        long id = graded(
                TestOutcome.FAILED,
                TestOutcome.PASSED,
                TestOutcome.PASSED,
                TestOutcome.RUNTIME_ERROR,
                TestOutcome.FAILED,
                TestOutcome.TIMEOUT);
        expectOneCall();

        HttpResponse<String> response = hint(id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        wire.server().verify();
        String body = sent.get(0);
        List<Map<String, Object>> hidden = JsonPath.read(body, "$.hiddenTests");
        assertThat(hidden).allSatisfy(h -> assertThat(h).containsOnlyKeys("passed"));
        assertThat(hidden)
                .extracting(h -> h.get("passed"))
                .as("in run order: hidden 1 failed, 2 passed, 3 runtime error, 4 timeout")
                .containsExactly(false, true, false, false);
        assertNoHiddenDetailIn(body);
    }

    @Test
    void publicTestsLeaveInFullBesideThem() throws Exception {
        long id = graded(
                TestOutcome.PASSED,
                TestOutcome.PASSED,
                TestOutcome.PASSED,
                TestOutcome.PASSED,
                TestOutcome.FAILED,
                TestOutcome.PASSED);
        expectOneCall();

        hint(id);

        String body = sent.get(0);
        assertThat(JsonPath.<List<String>>read(body, "$.publicTests[*].name")).containsExactly("adds two", "negatives");
        assertThat(JsonPath.<String>read(body, "$.publicTests[1].input")).isEqualTo("-1 -2");
        assertThat(JsonPath.<String>read(body, "$.publicTests[1].expectedOutput"))
                .isEqualTo("-3");
        assertThat(JsonPath.<String>read(body, "$.publicTests[1].status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(body, "$.publicTests[1].actualOutput")).isEqualTo("public actual 4");
        assertThat(JsonPath.<String>read(body, "$.publicTests[1].stderr")).isEqualTo("public stderr 4");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.hiddenTests[*].passed"))
                .containsExactly(true, true, true, true);
        assertNoHiddenDetailIn(body);
    }

    @Test
    void everyLevelSendsTheSameHiddenSectionAndNoDetail() throws Exception {
        long id = graded(
                TestOutcome.PASSED,
                TestOutcome.FAILED,
                TestOutcome.FAILED,
                TestOutcome.PASSED,
                TestOutcome.PASSED,
                TestOutcome.MEMORY_LIMIT);
        expectCalls(3);

        for (int level = 1; level <= 3; level++) {
            assertThat(hint(id).statusCode()).isEqualTo(201);
        }

        wire.server().verify();
        assertThat(sent).hasSize(3);
        for (int level = 1; level <= 3; level++) {
            String body = sent.get(level - 1);
            assertThat(JsonPath.<Integer>read(body, "$.hintLevel")).isEqualTo(level);
            assertThat(JsonPath.<List<Boolean>>read(body, "$.hiddenTests[*].passed"))
                    .containsExactly(true, false, true, false);
            assertNoHiddenDetailIn(body);
        }
    }

    @Test
    void aCompileErrorSendsEveryHiddenTestAsFailedAndNothingElse() throws Exception {
        long id = submission();
        writer.store(
                id,
                assignment.getId(),
                tests,
                tests.stream()
                        .map(t -> new TestResult(
                                id, t.getId(), TestOutcome.COMPILE_ERROR, null, "SyntaxError: invalid syntax", null))
                        .toList());
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        expectOneCall();

        assertThat(hint(id).statusCode()).isEqualTo(201);

        String body = sent.get(0);
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.hiddenTests"))
                .hasSize(4)
                .allSatisfy(h -> assertThat(h).containsOnlyKeys("passed").containsEntry("passed", false));
        assertThat(JsonPath.<String>read(body, "$.compilerOutput")).isEqualTo("SyntaxError: invalid syntax");
        assertNoHiddenDetailIn(body);
    }

    @Test
    void theMarkersAreStoredAndTheOwnerSeesThemSoTheSearchCanFindThem() throws Exception {
        long id = graded(
                TestOutcome.FAILED,
                TestOutcome.PASSED,
                TestOutcome.PASSED,
                TestOutcome.FAILED,
                TestOutcome.FAILED,
                TestOutcome.FAILED);

        HttpResponse<String> teacherView = get("/submissions/" + id, tokenFor(owner));

        assertThat(teacherView.statusCode()).isEqualTo(200);
        for (int test = 1; test <= 4; test++) {
            for (String what : WHAT) {
                assertThat(teacherView.body()).contains(marker(what, test));
            }
        }
    }

    // ---- helpers ----

    private void assertNoHiddenDetailIn(String body) {
        for (int test = 1; test <= 4; test++) {
            for (String what : WHAT) {
                assertThat(body).doesNotContain(marker(what, test));
            }
        }
        assertThat(body).doesNotContain("7b2e").doesNotContain("Hidden test");
    }

    private void expectOneCall() {
        expectCalls(1);
    }

    /** The AI service's `POST /feedback` with the token, answered with a hint; the body as written is kept. */
    private void expectCalls(int times) {
        wire.server()
                .expect(
                        ExpectedCount.times(times),
                        request -> assertThat(request.getURI().getPath()).isEqualTo("/feedback"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token()))
                .andExpect(request -> sent.add(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(
                        "{\"hint\": \"Look at the operator.\", \"fallback\": false}", MediaType.APPLICATION_JSON));
    }

    private HttpResponse<String> hint(long submissionId) throws Exception {
        return post("/submissions/" + submissionId + "/hints", "", tokenFor(student));
    }

    private TestCase hidden(long criterion, int test, int position) {
        return testCases.save(new TestCase(
                assignment.getId(),
                criterion,
                marker("NAME", test),
                marker("INPUT", test),
                marker("EXPECTED", test),
                TestVisibility.HIDDEN,
                1,
                position));
    }

    private long submission() {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(sum(map(int, input().split())))', 1, 'RUNNING') returning id",
                Long.class,
                assignment.getId(),
                student.getId());
    }

    /**
     * Graded with these outcomes, one per test in run order. A hidden test's output and stderr hold
     * its markers; a public test's are plain text naming its place.
     */
    private long graded(TestOutcome... outcomes) {
        long id = submission();
        List<TestResult> results = new ArrayList<>();
        int hiddenSeen = 0;
        for (int i = 0; i < tests.size(); i++) {
            TestCase t = tests.get(i);
            boolean hidden = t.getVisibility() == TestVisibility.HIDDEN;
            int n = hidden ? ++hiddenSeen : i;
            results.add(new TestResult(
                    id,
                    t.getId(),
                    outcomes[i],
                    hidden ? marker("ACTUAL", n) : "public actual " + n,
                    hidden ? marker("STDERR", n) : "public stderr " + n,
                    10 + i));
        }
        writer.store(id, assignment.getId(), tests, results);
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }
}
