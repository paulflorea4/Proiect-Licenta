package com.gradingplatform.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.controller.GradeController;
import com.gradingplatform.backend.controller.SubmissionController;
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
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 5.6c: the authorization and masking matrix of Phase 5, the counterpart of {@link
 * AuthorizationMatrixTests} (3.6a). Every submission and grade endpoint is called by the course's
 * owner, another teacher, the student the submission belongs to, a classmate, a student who is not
 * in the course and an admin, and must answer exactly as its row in {@link #ENDPOINTS} says. For
 * every row it also checks that a refused caller <b>changes nothing</b> (the tables of the course,
 * the submissions and their results, before and after), that a 404 is <b>byte-identical</b> to the
 * 404 of an id that does not exist, and that an anonymous caller is a 401.
 *
 * <p>The submitting row is given once per state of the assignment, so the rejections the rules
 * promise are in the matrix too: a draft (404 for every student), a deadline that has passed (409)
 * and an attempt limit that is used up (409 for the student who used it, a normal 202 for the
 * classmate who did not). A 409 is a refusal like any other, so those calls must change nothing.
 *
 * <p>The raw-JSON tests below the matrix check what a status cannot: which keys a student's
 * responses have, and that no hidden test's content, nor what the program printed on it, is in any
 * of them (a positive control shows the teacher gets it).
 */
class SubmissionAuthorizationMatrixTests extends RealHttpTestBase {

    private static final long MISSING = 987_654_321L;

    /** Strings that exist only inside the hidden test and its result. */
    private static final String[] SECRETS = {"SECRET-NAME", "SECRET-IN", "SECRET-OUT", "SECRET-PRINTED", "SECRET-STDERR"
    };

    private enum Actor {
        OWNER,
        OTHER_TEACHER,
        AUTHOR,
        CLASSMATE,
        OUTSIDER,
        ADMIN
    }

    /** The state of the world a row is called in. */
    private enum Setup {
        /** Published, deadline ahead, no limit, nobody has submitted; no test cases (a submission runs nothing). */
        OPEN,
        /** The same, unpublished. */
        DRAFT,
        /** The same as {@link #OPEN}, but the deadline was yesterday. */
        PAST_DEADLINE,
        /** The same as {@link #OPEN}, with {@code max_attempts} 1 and the author's one attempt used. */
        ATTEMPT_USED,
        /**
         * Published; a TESTS criterion (70) with a public and a hidden test, a MANUAL one (30); a graded
         * submission of the author and one of the classmate.
         */
        GRADED
    }

    /** Ids a path or body is built from. */
    private record Ids(long assignment, long submission, long manualCriterion) {

        static Ids missing() {
            return new Ids(MISSING, MISSING, MISSING);
        }
    }

    private record Row(
            String method,
            String pattern,
            Function<Ids, String> path,
            Function<Ids, String> body,
            Setup setup,
            Map<Actor, Integer> expected) {

        @Override
        public String toString() {
            return method + " " + pattern + " [" + setup + "]";
        }

        String key() {
            return method + " " + pattern;
        }
    }

    private static Row row(
            String method,
            String pattern,
            Function<Ids, String> path,
            Function<Ids, String> body,
            Setup setup,
            int owner,
            int otherTeacher,
            int author,
            int classmate,
            int outsider,
            int admin) {
        return new Row(
                method,
                pattern,
                path,
                body,
                setup,
                Map.of(
                        Actor.OWNER, owner,
                        Actor.OTHER_TEACHER, otherTeacher,
                        Actor.AUTHOR, author,
                        Actor.CLASSMATE, classmate,
                        Actor.OUTSIDER, outsider,
                        Actor.ADMIN, admin));
    }

    private static final Function<Ids, String> NO_BODY = ids -> null;
    private static final Function<Ids, String> CODE = ids -> "{\"sourceCode\":\"print(1)\"}";
    private static final Function<Ids, String> SCORE = ids -> "{\"score\":12.5,\"comment\":\"fine\"}";

    private static final Function<Ids, String> SUBMIT_PATH = i -> "/assignments/" + i.assignment() + "/submissions";
    private static final String SUBMIT = "/assignments/{assignmentId}/submissions";
    private static final String MY_SUBMISSIONS = "/assignments/{assignmentId}/my-submissions";
    private static final Function<Ids, String> MY_SUBMISSIONS_PATH =
            i -> "/assignments/" + i.assignment() + "/my-submissions";
    private static final String MY_GRADE = "/assignments/{assignmentId}/my-grade";
    private static final Function<Ids, String> MY_GRADE_PATH = i -> "/assignments/" + i.assignment() + "/my-grade";

    /**
     * One row per Phase 5 endpoint and state; the six numbers are the status for the owner, another
     * teacher, the author, a classmate, a student outside the course and an admin, in that order.
     */
    private static final List<Row> ENDPOINTS = List.of(
            // --- submitting: students of the course only, whatever the state of the assignment says ----
            row("POST", SUBMIT, SUBMIT_PATH, CODE, Setup.OPEN, 403, 403, 202, 202, 404, 403),
            row("POST", SUBMIT, SUBMIT_PATH, CODE, Setup.DRAFT, 403, 403, 404, 404, 404, 403),
            // A late submission is refused for a student of the course; for an outsider it is still the
            // 404 of a missing assignment, so the deadline tells nothing about an assignment they cannot see.
            row("POST", SUBMIT, SUBMIT_PATH, CODE, Setup.PAST_DEADLINE, 403, 403, 409, 409, 404, 403),
            // The limit counts each student's own attempts: the classmate has not used theirs.
            row("POST", SUBMIT, SUBMIT_PATH, CODE, Setup.ATTEMPT_USED, 403, 403, 409, 202, 404, 403),

            // --- one submission: its author, and the people who run the course -------------------------
            // Another student's existing submission is a 403 (the human's decision at 5.5a); another teacher
            // gets the 404 of a missing id.
            row(
                    "GET",
                    "/submissions/{submissionId}",
                    i -> "/submissions/" + i.submission(),
                    NO_BODY,
                    Setup.GRADED,
                    200,
                    404,
                    200,
                    403,
                    403,
                    200),

            // --- lists -------------------------------------------------------------------------------
            row("GET", SUBMIT, SUBMIT_PATH, NO_BODY, Setup.GRADED, 200, 404, 403, 403, 403, 200),
            row("GET", SUBMIT, SUBMIT_PATH, NO_BODY, Setup.DRAFT, 200, 404, 403, 403, 403, 200),
            row("GET", MY_SUBMISSIONS, MY_SUBMISSIONS_PATH, NO_BODY, Setup.GRADED, 403, 403, 200, 200, 404, 403),
            row("GET", MY_SUBMISSIONS, MY_SUBMISSIONS_PATH, NO_BODY, Setup.DRAFT, 403, 403, 404, 404, 404, 403),
            row("GET", MY_GRADE, MY_GRADE_PATH, NO_BODY, Setup.GRADED, 403, 403, 200, 200, 404, 403),
            row("GET", MY_GRADE, MY_GRADE_PATH, NO_BODY, Setup.DRAFT, 403, 403, 404, 404, 404, 403),

            // --- a manual score: the people who run the course, never a student -----------------------
            row(
                    "PUT",
                    "/submissions/{submissionId}/manual-scores/{criterionId}",
                    i -> "/submissions/" + i.submission() + "/manual-scores/" + i.manualCriterion(),
                    SCORE,
                    Setup.GRADED,
                    200,
                    404,
                    403,
                    403,
                    403,
                    200));

    static Stream<Arguments> rowsAndActors() {
        return ENDPOINTS.stream().flatMap(r -> Arrays.stream(Actor.values()).map(a -> Arguments.of(r, a)));
    }

    static Stream<Row> rows() {
        return ENDPOINTS.stream();
    }

    // --- the world ---------------------------------------------------------------------------

    @Autowired
    private SubmissionResultWriter writer;

    private record World(Map<Actor, User> users, Ids ids, long classmateSubmission) {

        String token(RealHttpTestBase base, Actor actor) {
            return base.tokenFor(users.get(actor));
        }
    }

    private long insertSubmission(long assignmentId, User by, int attemptNo, String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', ?, ?) returning id",
                Long.class,
                assignmentId,
                by.getId(),
                attemptNo,
                status);
    }

    private World buildWorld(Setup setup) {
        User owner = saved("owner@example.com", Role.TEACHER);
        User other = saved("other@example.com", Role.TEACHER);
        User author = saved("author@example.com", Role.STUDENT);
        User classmate = saved("classmate@example.com", Role.STUDENT);
        User outsider = saved("outsider@example.com", Role.STUDENT);
        User admin = saved("root@example.com", Role.ADMIN);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), author.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
        Assignment assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                setup == Setup.ATTEMPT_USED ? 1 : null,
                2000,
                256,
                null));
        if (setup != Setup.DRAFT) {
            jdbc.update("update assignments set published = true where id = ?", assignment.getId());
        }
        if (setup == Setup.PAST_DEADLINE) {
            jdbc.update("update assignments set deadline = now() - interval '1 day' where id = ?", assignment.getId());
        }
        long submissionId = 0;
        long classmateSubmission = 0;
        long manualId = 0;
        if (setup == Setup.GRADED) {
            RubricCriterion tests =
                    rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 70));
            manualId = rubricCriteria
                    .save(new RubricCriterion(assignment.getId(), "M", CriterionType.MANUAL, 30))
                    .getId();
            TestCase publicTest = testCases.save(new TestCase(
                    assignment.getId(), tests.getId(), "adds two", "1 2", "3", TestVisibility.PUBLIC, 1, 1));
            TestCase hiddenTest = testCases.save(new TestCase(
                    assignment.getId(),
                    tests.getId(),
                    SECRETS[0],
                    SECRETS[1],
                    SECRETS[2],
                    TestVisibility.HIDDEN,
                    1,
                    2));
            submissionId = graded(assignment, author, publicTest, hiddenTest);
            classmateSubmission = graded(assignment, classmate, publicTest, hiddenTest);
        } else {
            rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 100));
            if (setup == Setup.ATTEMPT_USED) {
                insertSubmission(assignment.getId(), author, 1, "GRADED");
            }
        }
        return new World(
                Map.of(
                        Actor.OWNER, owner,
                        Actor.OTHER_TEACHER, other,
                        Actor.AUTHOR, author,
                        Actor.CLASSMATE, classmate,
                        Actor.OUTSIDER, outsider,
                        Actor.ADMIN, admin),
                new Ids(assignment.getId(), submissionId, manualId),
                classmateSubmission);
    }

    /** Graded as the worker grades it: the public test passed, the hidden one failed with output of its own. */
    private long graded(Assignment assignment, User by, TestCase publicTest, TestCase hiddenTest) {
        long id = insertSubmission(assignment.getId(), by, 1, "RUNNING");
        writer.store(
                id,
                assignment.getId(),
                List.of(publicTest, hiddenTest),
                List.of(
                        new TestResult(id, publicTest.getId(), TestOutcome.PASSED, "3\n", "a warning", 12),
                        new TestResult(id, hiddenTest.getId(), TestOutcome.FAILED, SECRETS[3], SECRETS[4], 13)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }

    /** Everything a Phase 5 call can touch, as plain data, to compare before and after a refused call. */
    private List<Object> snapshot() {
        return List.of(
                jdbc.queryForList("select * from courses order by id"),
                jdbc.queryForList("select * from enrollments order by course_id, student_id"),
                jdbc.queryForList("select * from assignments order by id"),
                jdbc.queryForList("select * from rubric_criteria order by id"),
                jdbc.queryForList("select * from test_cases order by id"),
                jdbc.queryForList("select * from submissions order by id"),
                jdbc.queryForList("select * from test_results order by id"),
                jdbc.queryForList("select * from grades order by id"),
                jdbc.queryForList("select * from criterion_scores order by id"));
    }

    private HttpResponse<String> call(Row row, Ids ids, String token) throws Exception {
        return send(row.method(), row.path().apply(ids), token, row.body().apply(ids));
    }

    // --- the matrix --------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("rowsAndActors")
    void everyCallerGetsTheStatusItsRowSays(Row row, Actor actor) throws Exception {
        World world = buildWorld(row.setup());

        HttpResponse<String> response = call(row, world.ids(), world.token(this, actor));

        assertThat(response.statusCode())
                .as("%s as %s: %s", row, actor, response.body())
                .isEqualTo(row.expected().get(actor));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("rowsAndActors")
    void aRefusedCallerChangesNothing(Row row, Actor actor) throws Exception {
        int expected = row.expected().get(actor);
        if (expected < 400) {
            return; // a caller who may pass is meant to change things
        }
        World world = buildWorld(row.setup());
        List<Object> before = snapshot();

        HttpResponse<String> response = call(row, world.ids(), world.token(this, actor));

        assertThat(response.statusCode()).isEqualTo(expected);
        assertThat(snapshot()).as("%s as %s changed the data", row, actor).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("rowsAndActors")
    void aNotFoundIsByteIdenticalToTheNotFoundOfAnIdThatDoesNotExist(Row row, Actor actor) throws Exception {
        if (row.expected().get(actor) != 404) {
            return;
        }
        World world = buildWorld(row.setup());
        String token = world.token(this, actor);

        HttpResponse<String> real = call(row, world.ids(), token);
        HttpResponse<String> missing = call(row, Ids.missing(), token);

        assertThat(real.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(real.body())
                .as("%s as %s tells a taken id from a missing one", row, actor)
                .isEqualTo(missing.body());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void anAnonymousCallerIsUnauthenticatedEverywhere(Row row) throws Exception {
        World world = buildWorld(row.setup());
        List<Object> before = snapshot();

        HttpResponse<String> response = call(row, world.ids(), null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(snapshot()).isEqualTo(before);
    }

    // --- what the status alone does not show -------------------------------------------------

    @Test
    void theRefusalsOfTheSubmitRulesSayWhichRuleRefused() throws Exception {
        for (Map.Entry<Setup, String> expected : Map.of(
                        Setup.PAST_DEADLINE, "SUBMISSION_DEADLINE_PASSED", Setup.ATTEMPT_USED, "ATTEMPT_LIMIT_REACHED")
                .entrySet()) {
            emptyTables();
            World world = buildWorld(expected.getKey());

            HttpResponse<String> response = post(
                    "/assignments/" + world.ids().assignment() + "/submissions",
                    "{\"sourceCode\":\"print(1)\"}",
                    world.token(this, Actor.AUTHOR));

            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(expected.getValue());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Actor.class,
            names = {"OWNER", "OTHER_TEACHER", "CLASSMATE", "OUTSIDER", "ADMIN"})
    void theFileUploadVariantOfSubmittingFollowsTheSameRule(Actor actor) throws Exception {
        World world = buildWorld(Setup.OPEN);
        List<Object> before = snapshot();
        String boundary = "matrix-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"main.py\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\nprint(1)\r\n--" + boundary + "--\r\n";

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/assignments/"
                                + world.ids().assignment() + "/submissions"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Authorization", "Bearer " + world.token(this, actor))
                        .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // The same statuses as the text row for [OPEN]: a student of the course submits, an outsider
        // gets the 404 of a missing assignment, teachers and admins are refused by role.
        int expected = ENDPOINTS.get(0).expected().get(actor);
        assertThat(response.statusCode()).as("%s: %s", actor, response.body()).isEqualTo(expected);
        if (expected >= 400) {
            assertThat(snapshot()).as("%s changed the data", actor).isEqualTo(before);
        }
    }

    @Test
    void aStudentsViewsHaveOnlyTheKeysAStudentMayHaveAndNoHiddenContent() throws Exception {
        World world = buildWorld(Setup.GRADED);
        String token = world.token(this, Actor.AUTHOR);

        String view = get("/submissions/" + world.ids().submission(), token).body();
        String mine = get("/assignments/" + world.ids().assignment() + "/my-submissions", token)
                .body();
        String grade = get("/assignments/" + world.ids().assignment() + "/my-grade", token)
                .body();

        // The submission: no student id, and a criterion without its id or its weight (the ids are the
        // teacher's, for the manual-score endpoint).
        Map<String, Object> top = JsonPath.read(view, "$");
        assertThat(top)
                .containsOnlyKeys(
                        "id",
                        "assignmentId",
                        "attemptNo",
                        "status",
                        "language",
                        "sourceCode",
                        "submittedAt",
                        "startedAt",
                        "finishedAt",
                        "compilerOutput",
                        "grade",
                        "criteria",
                        "tests",
                        "hintsUsed");
        assertThat(JsonPath.<List<Map<String, Object>>>read(view, "$.criteria"))
                .hasSize(2)
                .allSatisfy(c -> assertThat(c).containsOnlyKeys("name", "type", "score", "maxScore", "comment"));
        // The hidden test: a masked name, its place and whether it passed. Nothing it contains, nothing
        // its program printed, no outcome and no run time.
        assertThat(JsonPath.<Map<String, Object>>read(view, "$.tests[1]"))
                .containsOnlyKeys("id", "name", "visibility", "position", "passed")
                .containsEntry("name", "Hidden test 1")
                .containsEntry("passed", false);
        assertThat(JsonPath.<Map<String, Object>>read(view, "$.tests[0]"))
                .containsKeys("input", "expectedOutput", "actualOutput", "status");

        // The list: a row has no student, no code and no results.
        assertThat(JsonPath.<List<Map<String, Object>>>read(mine, "$.items"))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row)
                        .containsOnlyKeys("id", "attemptNo", "status", "submittedAt", "finishedAt", "grade"));
        // The grade: the best attempt's score, nothing of the tests.
        assertThat(JsonPath.<Map<String, Object>>read(grade, "$.best"))
                .containsOnlyKeys("submissionId", "attemptNo", "totalScore", "maxScore", "provisional", "gradedAt");

        assertThat(view + mine + grade)
                .as("hidden test content or what the program printed on it reached a student")
                .doesNotContain(SECRETS);
    }

    @Test
    void theTeacherGetsTheHiddenContentThatTheStudentDoesNotAndTheListsKeepStudentsApart() throws Exception {
        World world = buildWorld(Setup.GRADED);

        String teacherView = get("/submissions/" + world.ids().submission(), world.token(this, Actor.OWNER))
                .body();
        String teacherList = get(
                        "/assignments/" + world.ids().assignment() + "/submissions", world.token(this, Actor.OWNER))
                .body();

        // Positive control: the markers are in the data, so their absence above means something.
        assertThat(teacherView).contains(SECRETS);
        assertThat(JsonPath.<Map<String, Object>>read(teacherView, "$")).containsKey("studentId");
        // The teacher's list has both students' submissions, each with its student, and no code.
        assertThat(JsonPath.<List<Map<String, Object>>>read(teacherList, "$.items"))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row)
                        .containsOnlyKeys(
                                "id", "student", "attemptNo", "status", "submittedAt", "finishedAt", "grade"));

        // A student's own list holds only their own submission, never the classmate's.
        for (Actor actor : List.of(Actor.AUTHOR, Actor.CLASSMATE)) {
            List<Integer> ids = JsonPath.read(
                    get("/assignments/" + world.ids().assignment() + "/my-submissions", world.token(this, actor))
                            .body(),
                    "$.items[*].id");
            long own = actor == Actor.AUTHOR ? world.ids().submission() : world.classmateSubmission();
            assertThat(ids).as("%s sees only their own submission", actor).containsExactly((int) own);
        }
    }

    @Test
    void theRefusalOfAnotherStudentsSubmissionIsTheSameForEveryStudentAndShowsNothingOfIt() throws Exception {
        World world = buildWorld(Setup.GRADED);
        String path = "/submissions/" + world.ids().submission();

        HttpResponse<String> classmate = get(path, world.token(this, Actor.CLASSMATE));
        HttpResponse<String> outsider = get(path, world.token(this, Actor.OUTSIDER));

        assertThat(classmate.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(classmate.body(), "$.code")).isEqualTo("ACCESS_DENIED");
        assertThat(outsider.body()).isEqualTo(classmate.body());
        assertThat(classmate.body()).doesNotContain("print(1)").doesNotContain(SECRETS);
    }

    // --- completeness ------------------------------------------------------------------------

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyPhase5EndpointHasARow() {
        Set<Class<?>> phase5 = Set.of(SubmissionController.class, GradeController.class);
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!phase5.contains(handler.getBeanType())) {
                return;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    mapped.add(method.name() + " " + pattern);
                }
            }
        });

        Set<String> inMatrix = new TreeSet<>();
        ENDPOINTS.forEach(row -> inMatrix.add(row.key()));

        assertThat(inMatrix)
                .as("Phase 5 endpoints mapped by Spring vs rows of ENDPOINTS in SubmissionAuthorizationMatrixTests;"
                        + " add a row for each new endpoint (a multipart variant shares its row)")
                .isEqualTo(mapped);
    }

    @Test
    void theMatrixIsComplete() {
        // Six callers per row, and a status for each.
        assertThat(ENDPOINTS).allSatisfy(row -> assertThat(row.expected()).hasSize(Actor.values().length));
    }
}
