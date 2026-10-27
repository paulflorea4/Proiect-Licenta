package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gradingplatform.backend.ai.AiServiceProperties;
import com.gradingplatform.backend.entity.AiFeedback;
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
import com.gradingplatform.backend.repository.AiFeedbackRepository;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionResultWriter;
import com.jayway.jsonpath.JsonPath;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;

/**
 * 7.5c: the hint request's refusals and failures end to end, with the real {@link
 * com.gradingplatform.backend.ai.AiServiceClient} and Spring's {@code MockRestServiceServer} playing
 * the AI service on the wire (the context of {@link RealHttpHintOutgoingRequestTests}). Another
 * student is refused before anything is sent; every way the AI service can fail is the same 503 with
 * nothing changed; a refused token is one call, logged as a configuration error, and not retried.
 */
@Import(RealHttpHintOutgoingRequestTests.MockAiService.class)
@ExtendWith(OutputCaptureExtension.class)
class RealHttpHintFailureTests extends RealHttpTestBase {

    private static final String HINTS_UNAVAILABLE_MESSAGE =
            "Hints are unavailable right now; your submission and its grade are not affected. Try again later";

    @Autowired
    private RealHttpHintOutgoingRequestTests.MockAiService.Wire wire;

    @Autowired
    private AiServiceProperties properties;

    @Autowired
    private SubmissionResultWriter writer;

    @Autowired
    private AiFeedbackRepository hints;

    private User owner;
    private User student;
    private User classmate;
    private Assignment assignment;
    private TestCase p1;
    private TestCase h1;
    private long submission;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "FAIL2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
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
        RubricCriterion tests =
                rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 100));
        p1 = testCases.save(
                new TestCase(assignment.getId(), tests.getId(), "adds two", "1 2", "3", TestVisibility.PUBLIC, 1, 1));
        h1 = testCases.save(
                new TestCase(assignment.getId(), tests.getId(), "hidden", "4 5", "9", TestVisibility.HIDDEN, 1, 2));
        submission = graded();
        wire.server().reset();
    }

    @AfterEach
    void noHintLeftBehind() {
        jdbc.update("delete from ai_feedback");
    }

    // ---- another student: refused before anything is sent ----

    @Test
    void anotherStudentsSubmissionIs403AndNothingReachesTheAiService() throws Exception {
        String before = snapshot();

        HttpResponse<String> response = hint(classmate);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("ACCESS_DENIED");
        assertThat(snapshot()).isEqualTo(before);
        // No expectation was set: any request to the AI service would have failed this.
        wire.server().verify();
    }

    @Test
    void anotherStudentCannotReadOrReportTheHintsEither() throws Exception {
        hints.save(new AiFeedback(submission, 1, "Look at the operator."));
        String before = snapshot();

        HttpResponse<String> list = get("/submissions/" + submission + "/hints", tokenFor(classmate));
        HttpResponse<String> report =
                post("/submissions/" + submission + "/hints/1/report", "{\"reason\":\"x\"}", tokenFor(classmate));

        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(list.body()).doesNotContain("Look at the operator.");
        assertThat(report.statusCode()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
        wire.server().verify();
    }

    @Test
    void theCourseOwnerCannotAskForAStudentsHint() throws Exception {
        HttpResponse<String> response = hint(owner);

        assertThat(response.statusCode()).isEqualTo(403);
        wire.server().verify();
    }

    // ---- the AI service fails: 503, nothing changed, one call ----

    /** What the AI service can answer, or fail to, on the wire. */
    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of("503 unavailable", withStatus(HttpStatus.SERVICE_UNAVAILABLE)),
                Arguments.of("500 error", withStatus(HttpStatus.INTERNAL_SERVER_ERROR)),
                Arguments.of("502 bad gateway", withStatus(HttpStatus.BAD_GATEWAY)),
                Arguments.of("504 gateway timeout", withStatus(HttpStatus.GATEWAY_TIMEOUT)),
                Arguments.of("422 refused request", withStatus(HttpStatus.UNPROCESSABLE_CONTENT)),
                Arguments.of("429 too many", withStatus(HttpStatus.TOO_MANY_REQUESTS)),
                Arguments.of(
                        "302 redirect",
                        withStatus(HttpStatus.FOUND).location(URI.create("http://elsewhere.invalid/feedback"))),
                Arguments.of("timed out", withException(new HttpTimeoutException("request timed out"))),
                Arguments.of("unreachable", withException(new ConnectException("Connection refused"))),
                Arguments.of("not JSON", withSuccess("<html>oops</html>", MediaType.TEXT_HTML)),
                Arguments.of(
                        "blank hint",
                        withSuccess("{\"hint\": \"  \", \"fallback\": false}", MediaType.APPLICATION_JSON)),
                Arguments.of("no fallback flag", withSuccess("{\"hint\": \"Look.\"}", MediaType.APPLICATION_JSON)),
                Arguments.of(
                        "an error body that echoes",
                        withStatus(HttpStatus.BAD_GATEWAY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"detail\": \"ECHO-OF-THE-REQUEST print(sum(map(int\"}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void anAiFailureIsA503WithNothingChangedAfterOneCall(String name, ResponseCreator answer) throws Exception {
        String before = snapshot();
        expect(ExpectedCount.once()).andRespond(answer);

        HttpResponse<String> response = hint(student);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("HINTS_UNAVAILABLE");
        assertThat(JsonPath.<String>read(response.body(), "$.message")).isEqualTo(HINTS_UNAVAILABLE_MESSAGE);
        assertThat(response.body()).doesNotContain("ECHO-OF-THE-REQUEST").doesNotContain("HTTP");
        assertThat(snapshot()).isEqualTo(before);
        wire.server().verify();
    }

    @Test
    void afterAFailureTheStudentAsksAgainAndTheSameLevelIsStored() throws Exception {
        expect(ExpectedCount.once()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expect(ExpectedCount.once())
                .andRespond(withSuccess(
                        "{\"hint\": \"Look at the operator.\", \"fallback\": false}", MediaType.APPLICATION_JSON));

        HttpResponse<String> failed = hint(student);
        HttpResponse<String> retried = hint(student);

        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(retried.body(), "$.level")).isEqualTo(1);
        wire.server().verify();
    }

    // ---- a refused token: a configuration error, not retried ----

    @Test
    void aRefusedTokenIsOneCallAndAConfigurationErrorInTheLog(CapturedOutput output) throws Exception {
        String before = snapshot();
        expect(ExpectedCount.once()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        HttpResponse<String> response = hint(student);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("HINTS_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("token").doesNotContain("AI_SERVICE_TOKEN");
        assertThat(snapshot()).isEqualTo(before);
        // ExpectedCount.once(): a second attempt would fail verify (and the request itself).
        wire.server().verify();
        assertThat(output.getOut())
                .contains("ERROR")
                .contains("The AI service refused the backend's token (HTTP 401)")
                .contains("AI_SERVICE_TOKEN must be the AI service's own")
                .doesNotContain(properties.token());
    }

    @Test
    void aForbiddenAnswerIsTheSameConfigurationError(CapturedOutput output) throws Exception {
        expect(ExpectedCount.once()).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(hint(student).statusCode()).isEqualTo(503);

        wire.server().verify();
        assertThat(output.getOut()).contains("The AI service refused the backend's token (HTTP 403)");
    }

    @Test
    void eachRequestAfterARefusedTokenCallsOnceAgainNothingIsCached() throws Exception {
        expect(ExpectedCount.times(2)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(hint(student).statusCode()).isEqualTo(503);
        assertThat(hint(student).statusCode()).isEqualTo(503);

        wire.server().verify();
    }

    @Test
    void anOutageIsAWarningNotAConfigurationError(CapturedOutput output) throws Exception {
        expect(ExpectedCount.once()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        hint(student);

        assertThat(output.getOut())
                .contains("WARN")
                .contains("No hint for submission " + submission + " at level 1: The AI service answered HTTP 503")
                .doesNotContain("refused the backend's token")
                .doesNotContain(properties.token());
    }

    // ---- helpers ----

    /** One expected `POST /feedback` carrying the backend's token. */
    private ResponseActions expect(ExpectedCount count) {
        return wire.server()
                .expect(count, request -> assertThat(request.getURI().getPath()).isEqualTo("/feedback"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token()));
    }

    private HttpResponse<String> hint(User as) throws Exception {
        return post("/submissions/" + submission + "/hints", "", tokenFor(as));
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    /** Everything stored about the submission: its row, grade, criterion scores, results and hints. */
    private String snapshot() {
        return String.join(
                "\n",
                jdbc.queryForList("select * from submissions where id = ?", submission)
                        .toString(),
                jdbc.queryForList("select * from grades where submission_id = ?", submission)
                        .toString(),
                jdbc.queryForList(
                                "select * from criterion_scores where submission_id = ? order by criterion_id",
                                submission)
                        .toString(),
                jdbc.queryForList("select * from test_results where submission_id = ? order by id", submission)
                        .toString(),
                jdbc.queryForList("select * from ai_feedback where submission_id = ? order by hint_level", submission)
                        .toString());
    }

    /** Graded as the worker grades it: p1 passed, h1 failed, so hints may be asked for (7.2c). */
    private long graded() {
        long id = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(sum(map(int, input().split())))', 1, 'RUNNING') returning id",
                Long.class,
                assignment.getId(),
                student.getId());
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.PASSED, "3\n", "", 12),
                        new TestResult(id, h1.getId(), TestOutcome.FAILED, "8\n", "", 13)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }
}
