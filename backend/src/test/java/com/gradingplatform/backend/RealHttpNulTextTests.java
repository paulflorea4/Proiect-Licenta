package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.AiFeedback;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.AiFeedbackRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * 7.6a: a NUL character (U+0000) in any free-text field of a request body is a 400 {@code
 * VALIDATION_FAILED} naming the field, before anything is stored, instead of the 500 Postgres'
 * {@code TEXT} gave when it refused the value. One row per text field of every endpoint that takes a
 * body; each row also sends the same body without the NUL and expects the endpoint's usual answer,
 * so the 400 is the NUL's doing. The guard that every request record's text field carries the
 * constraint is {@code dto.NoNulTests}.
 */
class RealHttpNulTextTests extends RealHttpTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEADLINE = Instant.now()
            .plus(30, ChronoUnit.DAYS)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString();

    enum Caller {
        NOBODY,
        OWNER,
        STUDENT
    }

    /** One text field of one endpoint: the body as a map, the field the NUL goes into, the clean answer. */
    record Row(String method, String path, Caller caller, Map<String, Object> body, String field, int cleanStatus) {

        @Override
        public String toString() {
            return method + " " + path + " ." + field;
        }
    }

    @Autowired
    private AiFeedbackRepository hints;

    private User owner;
    private User student;
    private Course course;
    private Assignment draft;
    private RubricCriterion criterion;
    private TestCase test;
    private RubricCriterion manual;
    private long submission;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "NULL2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        courses.save(new Course("Another", null, owner.getId(), "JOIN2345"));
        // A draft with no submissions, so its rubric and tests may be changed.
        draft = assignments.save(assignment("Draft"));
        criterion = rubricCriteria.save(new RubricCriterion(draft.getId(), "Tests", CriterionType.TESTS, 100));
        test = testCases.save(
                new TestCase(draft.getId(), criterion.getId(), "adds", "1 2", "3", TestVisibility.PUBLIC, 1, 1));
        // A published one with a graded submission, a manual criterion and a hint.
        Assignment published = assignments.save(assignment("Published"));
        jdbc.update("update assignments set published = true where id = ?", published.getId());
        manual = rubricCriteria.save(new RubricCriterion(published.getId(), "Style", CriterionType.MANUAL, 100));
        submission = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', 1, 'GRADED') returning id",
                Long.class,
                published.getId(),
                student.getId());
        hints.save(new AiFeedback(submission, 1, "Look at the last line."));
    }

    private Assignment assignment(String title) {
        return new Assignment(
                course.getId(),
                title,
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null);
    }

    static Stream<Arguments> textFields() {
        Map<String, Object> signup = body("email", "new@example.com", "password", "password123", "fullName", "New One");
        Map<String, Object> signin = body("email", "student@example.com", "password", "password123");
        Map<String, Object> course = body("title", "Graphs", "description", "Paths and trees");
        Map<String, Object> assignment = body(
                "title",
                "Sum",
                "description",
                "Add two numbers",
                "language",
                "PYTHON",
                "deadline",
                DEADLINE,
                "timeLimitMs",
                2000,
                "memoryLimitMb",
                256,
                "starterCode",
                "print()");
        Map<String, Object> newCriterion = body("name", "More tests", "type", "TESTS", "weight", 10);
        Map<String, Object> sameCriterion = body("name", "Renamed", "type", "TESTS", "weight", 100);
        Map<String, Object> testCase = body(
                "criterionId",
                "{criterion}",
                "name",
                "adds more",
                "input",
                "2 3",
                "expectedOutput",
                "5",
                "visibility",
                "PUBLIC",
                "weight",
                1);
        return Stream.of(
                        rows("POST", "/auth/signup", Caller.NOBODY, signup, 201, "email", "fullName"),
                        rows("POST", "/auth/signin", Caller.NOBODY, signin, 401, "email"),
                        rows("POST", "/courses", Caller.OWNER, course, 201, "title", "description"),
                        rows("PUT", "/courses/{course}", Caller.OWNER, course, 200, "title", "description"),
                        rows("POST", "/courses/enroll", Caller.STUDENT, body("code", "JOIN2345"), 200, "code"),
                        rows(
                                "POST",
                                "/courses/{course}/assignments",
                                Caller.OWNER,
                                assignment,
                                201,
                                "title",
                                "description",
                                "language",
                                "starterCode"),
                        rows(
                                "PUT",
                                "/assignments/{draft}",
                                Caller.OWNER,
                                assignment,
                                200,
                                "title",
                                "description",
                                "language",
                                "starterCode"),
                        rows("POST", "/assignments/{draft}/rubric", Caller.OWNER, newCriterion, 201, "name"),
                        rows(
                                "PUT",
                                "/assignments/{draft}/rubric/{criterion}",
                                Caller.OWNER,
                                sameCriterion,
                                200,
                                "name"),
                        rows(
                                "POST",
                                "/assignments/{draft}/tests",
                                Caller.OWNER,
                                testCase,
                                201,
                                "name",
                                "input",
                                "expectedOutput"),
                        rows(
                                "PUT",
                                "/assignments/{draft}/tests/{test}",
                                Caller.OWNER,
                                testCase,
                                200,
                                "name",
                                "input",
                                "expectedOutput"),
                        rows(
                                "PUT",
                                "/submissions/{submission}/manual-scores/{manual}",
                                Caller.OWNER,
                                body("score", 5, "comment", "Tidy"),
                                200,
                                "comment"),
                        rows(
                                "POST",
                                "/submissions/{submission}/hints/1/report",
                                Caller.STUDENT,
                                body("reason", "Wrong"),
                                200,
                                "reason"))
                .flatMap(s -> s)
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textFields")
    void aNulInTheFieldIsA400NamingItAndNothingChanges(Row row) throws Exception {
        String before = snapshot();

        HttpResponse<String> refused = send(row, withNul(row.body(), row.field()));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        // Only this field; the email of a signup is also refused by @Email, so it can be named twice.
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.fieldErrors[*].field"))
                .containsOnly(row.field());
        assertThat(refused.body()).doesNotContain("\\u0000").doesNotContain("x-nul-y");
        assertThat(snapshot()).as("a refused body changes nothing").isEqualTo(before);

        HttpResponse<String> clean = send(row, row.body());

        assertThat(clean.statusCode())
                .as("the same body without the NUL: %s", clean.body())
                .isEqualTo(row.cleanStatus());
    }

    // ---- helpers ----

    private HttpResponse<String> send(Row row, Map<String, Object> body) throws Exception {
        String token = switch (row.caller()) {
            case NOBODY -> null;
            case OWNER -> tokenFor(owner);
            case STUDENT -> tokenFor(student);
        };
        return send(row.method(), resolve(row.path()), token, resolve(JSON.writeValueAsString(body)));
    }

    private String resolve(String text) {
        return text.replace("{course}", String.valueOf(course.getId()))
                .replace("\"{criterion}\"", String.valueOf(criterion.getId()))
                .replace("{criterion}", String.valueOf(criterion.getId()))
                .replace("{draft}", String.valueOf(draft.getId()))
                .replace("{test}", String.valueOf(test.getId()))
                .replace("{submission}", String.valueOf(submission))
                .replace("{manual}", String.valueOf(manual.getId()));
    }

    /** The body with the field's value replaced by one holding a NUL between two plain parts. */
    private static Map<String, Object> withNul(Map<String, Object> body, String field) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(field, "x-nul" + '\u0000' + "-y" + (field.equals("email") ? "@example.com" : ""));
        return copy;
    }

    private static Stream<Row> rows(
            String method, String path, Caller caller, Map<String, Object> body, int cleanStatus, String... fields) {
        return Stream.of(fields).map(field -> new Row(method, path, caller, body, field, cleanStatus));
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    /** Every table a refused body could have written to. */
    private String snapshot() {
        return String.join(
                "\n",
                List.of(
                                "users order by id",
                                "courses order by id",
                                "enrollments order by course_id, student_id",
                                "assignments order by id",
                                "rubric_criteria order by id",
                                "test_cases order by id",
                                "criterion_scores order by submission_id, criterion_id",
                                "grades order by submission_id",
                                "ai_feedback order by id")
                        .stream()
                        .map(table ->
                                jdbc.queryForList("select * from " + table).toString())
                        .toList());
    }
}
