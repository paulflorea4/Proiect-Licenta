package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.controller.AssignmentController;
import com.gradingplatform.backend.controller.CourseController;
import com.gradingplatform.backend.controller.RubricController;
import com.gradingplatform.backend.controller.TestCaseController;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
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
 * 3.6a: the authorization matrix of Phase 3. Where {@link RoleMatrixIntegrationTests} asks "which
 * <i>role</i> may call this?", this asks "which <i>relationship</i> to the course may?": every
 * endpoint of courses, enrollment, assignments, rubric and tests is called by the course's owner,
 * another teacher, an enrolled student, a student who is not enrolled and an admin, and must answer
 * exactly as its row in {@link #ENDPOINTS} says.
 *
 * <p>Three things are checked for every row:
 * <ul>
 *   <li>the status each of the five callers gets;
 *   <li>that a caller who is refused changes <b>nothing</b> (a snapshot of the course tables before
 *       and after is identical);
 *   <li>that a 404 is <b>byte-identical</b> to the 404 for an id that does not exist, so no row
 *       tells a caller which ids are taken.
 * </ul>
 *
 * <p>{@link #everyPhase3EndpointHasARow} fails, naming the endpoint, when a controller method of
 * this phase has no row. The world is built per call, in the state each row names ({@link Setup}),
 * because most rows mutate it.
 */
class AuthorizationMatrixTests extends RealHttpTestBase {

    private static final long MISSING = 987_654_321L;

    private enum Actor {
        OWNER,
        OTHER_TEACHER,
        ENROLLED_STUDENT,
        OUTSIDER,
        ADMIN
    }

    /** The state of the world a row is called in. */
    private enum Setup {
        /** An unpublished assignment with a rubric (one `TESTS` criterion, weight 100) and one test. */
        DRAFT,
        /** The same, published. */
        PUBLISHED,
        /** Like {@link #DRAFT}, but the criterion has no test (so it can be deleted). */
        DRAFT_CRITERION_WITHOUT_TESTS,
        /** A course with no assignment at all (so it can be deleted). */
        EMPTY_COURSE
    }

    /** Ids a path or body is built from. */
    private record Ids(long course, long assignment, long criterion, long test, String enrollCode) {

        static Ids missing() {
            return new Ids(MISSING, MISSING, MISSING, MISSING, "ZZZZZZZZ");
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
            int enrolledStudent,
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
                        Actor.ENROLLED_STUDENT, enrolledStudent,
                        Actor.OUTSIDER, outsider,
                        Actor.ADMIN, admin));
    }

    private static final Function<Ids, String> NO_BODY = ids -> null;

    private static final String COURSE_BODY = "{\"title\":\"Databases\"}";

    private static String assignmentBody() {
        return "{\"title\":\"Sorting\",\"description\":\"Sort it\",\"language\":\"JAVA\","
                + "\"deadline\":\"2999-01-01T00:00:00Z\",\"timeLimitMs\":2000,\"memoryLimitMb\":256}";
    }

    private static final String CRITERION_BODY = "{\"name\":\"More tests\",\"type\":\"TESTS\",\"weight\":10}";

    private static String testBody(Ids ids) {
        return "{\"criterionId\":" + ids.criterion()
                + ",\"name\":\"t\",\"input\":\"1\",\"expectedOutput\":\"1\",\"visibility\":\"PUBLIC\",\"weight\":1}";
    }

    /**
     * One row per Phase 3 endpoint; the five numbers are the status for the owner, another teacher,
     * an enrolled student, a student who is not enrolled and an admin, in that order.
     */
    private static final List<Row> ENDPOINTS = List.of(
            // --- courses ---------------------------------------------------------------------
            // Any teacher may create a course (their own); students and admins may not.
            row("POST", "/courses", i -> "/courses", i -> COURSE_BODY, Setup.DRAFT, 201, 201, 403, 403, 403),
            row("GET", "/courses", i -> "/courses", NO_BODY, Setup.DRAFT, 200, 200, 200, 200, 200),
            row("GET", "/courses/{id}", i -> "/courses/" + i.course(), NO_BODY, Setup.DRAFT, 200, 404, 200, 404, 200),
            row(
                    "PUT",
                    "/courses/{id}",
                    i -> "/courses/" + i.course(),
                    i -> COURSE_BODY,
                    Setup.DRAFT,
                    200,
                    404,
                    403,
                    403,
                    200),
            row(
                    "DELETE",
                    "/courses/{id}",
                    i -> "/courses/" + i.course(),
                    NO_BODY,
                    Setup.EMPTY_COURSE,
                    204,
                    404,
                    403,
                    403,
                    204),
            // Students only: the owner's and the admin's roles are refused, a student joins or is already in.
            row(
                    "POST",
                    "/courses/enroll",
                    i -> "/courses/enroll",
                    i -> "{\"code\":\"" + i.enrollCode() + "\"}",
                    Setup.DRAFT,
                    403,
                    403,
                    200,
                    200,
                    403),
            row(
                    "DELETE",
                    "/courses/{id}/enrollment",
                    i -> "/courses/" + i.course() + "/enrollment",
                    NO_BODY,
                    Setup.DRAFT,
                    403,
                    403,
                    204,
                    404,
                    403),
            row(
                    "GET",
                    "/courses/{id}/students",
                    i -> "/courses/" + i.course() + "/students",
                    NO_BODY,
                    Setup.DRAFT,
                    200,
                    404,
                    403,
                    403,
                    200),

            // --- assignments -----------------------------------------------------------------
            // Admin is excluded from creating, as for courses.
            row(
                    "POST",
                    "/courses/{courseId}/assignments",
                    i -> "/courses/" + i.course() + "/assignments",
                    i -> assignmentBody(),
                    Setup.DRAFT,
                    201,
                    404,
                    403,
                    403,
                    403),
            row(
                    "GET",
                    "/courses/{courseId}/assignments",
                    i -> "/courses/" + i.course() + "/assignments",
                    NO_BODY,
                    Setup.PUBLISHED,
                    200,
                    404,
                    200,
                    404,
                    200),
            // A student reads a published assignment but gets the 404 of a missing one for a draft.
            row(
                    "GET",
                    "/assignments/{id}",
                    i -> "/assignments/" + i.assignment(),
                    NO_BODY,
                    Setup.PUBLISHED,
                    200,
                    404,
                    200,
                    404,
                    200),
            row(
                    "GET",
                    "/assignments/{id}",
                    i -> "/assignments/" + i.assignment(),
                    NO_BODY,
                    Setup.DRAFT,
                    200,
                    404,
                    404,
                    404,
                    200),
            row(
                    "PUT",
                    "/assignments/{id}",
                    i -> "/assignments/" + i.assignment(),
                    i -> assignmentBody(),
                    Setup.DRAFT,
                    200,
                    404,
                    403,
                    403,
                    200),
            row(
                    "POST",
                    "/assignments/{id}/publish",
                    i -> "/assignments/" + i.assignment() + "/publish",
                    NO_BODY,
                    Setup.DRAFT,
                    200,
                    404,
                    403,
                    403,
                    200),
            row(
                    "POST",
                    "/assignments/{id}/unpublish",
                    i -> "/assignments/" + i.assignment() + "/unpublish",
                    NO_BODY,
                    Setup.PUBLISHED,
                    200,
                    404,
                    403,
                    403,
                    200),
            row(
                    "DELETE",
                    "/assignments/{id}",
                    i -> "/assignments/" + i.assignment(),
                    NO_BODY,
                    Setup.DRAFT,
                    204,
                    404,
                    403,
                    403,
                    204),

            // --- rubric (students never see it) ----------------------------------------------
            row(
                    "GET",
                    "/assignments/{assignmentId}/rubric",
                    i -> "/assignments/" + i.assignment() + "/rubric",
                    NO_BODY,
                    Setup.PUBLISHED,
                    200,
                    404,
                    403,
                    403,
                    200),
            row(
                    "POST",
                    "/assignments/{assignmentId}/rubric",
                    i -> "/assignments/" + i.assignment() + "/rubric",
                    i -> CRITERION_BODY,
                    Setup.DRAFT,
                    201,
                    404,
                    403,
                    403,
                    201),
            row(
                    "PUT",
                    "/assignments/{assignmentId}/rubric/{criterionId}",
                    i -> "/assignments/" + i.assignment() + "/rubric/" + i.criterion(),
                    i -> CRITERION_BODY,
                    Setup.DRAFT,
                    200,
                    404,
                    403,
                    403,
                    200),
            row(
                    "DELETE",
                    "/assignments/{assignmentId}/rubric/{criterionId}",
                    i -> "/assignments/" + i.assignment() + "/rubric/" + i.criterion(),
                    NO_BODY,
                    Setup.DRAFT_CRITERION_WITHOUT_TESTS,
                    204,
                    404,
                    403,
                    403,
                    204),

            // --- tests ------------------------------------------------------------------------
            // The list is the one read students have: masked, and only for a published assignment.
            row(
                    "GET",
                    "/assignments/{assignmentId}/tests",
                    i -> "/assignments/" + i.assignment() + "/tests",
                    NO_BODY,
                    Setup.PUBLISHED,
                    200,
                    404,
                    200,
                    404,
                    200),
            row(
                    "GET",
                    "/assignments/{assignmentId}/tests",
                    i -> "/assignments/" + i.assignment() + "/tests",
                    NO_BODY,
                    Setup.DRAFT,
                    200,
                    404,
                    404,
                    404,
                    200),
            row(
                    "POST",
                    "/assignments/{assignmentId}/tests",
                    i -> "/assignments/" + i.assignment() + "/tests",
                    AuthorizationMatrixTests::testBody,
                    Setup.DRAFT,
                    201,
                    404,
                    403,
                    403,
                    201),
            row(
                    "PUT",
                    "/assignments/{assignmentId}/tests/{testId}",
                    i -> "/assignments/" + i.assignment() + "/tests/" + i.test(),
                    AuthorizationMatrixTests::testBody,
                    Setup.DRAFT,
                    200,
                    404,
                    403,
                    403,
                    200),
            row(
                    "DELETE",
                    "/assignments/{assignmentId}/tests/{testId}",
                    i -> "/assignments/" + i.assignment() + "/tests/" + i.test(),
                    NO_BODY,
                    Setup.DRAFT,
                    204,
                    404,
                    403,
                    403,
                    204));

    static Stream<Arguments> rowsAndActors() {
        return ENDPOINTS.stream().flatMap(r -> Arrays.stream(Actor.values()).map(a -> Arguments.of(r, a)));
    }

    // --- the world ---------------------------------------------------------------------------

    private record World(Map<Actor, User> users, Ids ids) {

        String token(RealHttpTestBase base, Actor actor) {
            return base.tokenFor(users.get(actor));
        }
    }

    private World buildWorld(Setup setup) {
        User owner = saved("owner@example.com", Role.TEACHER);
        User other = saved("other@example.com", Role.TEACHER);
        User enrolled = saved("enrolled@example.com", Role.STUDENT);
        User outsider = saved("outsider@example.com", Role.STUDENT);
        User admin = saved("root@example.com", Role.ADMIN);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), enrolled.getId()));
        long assignmentId = 0;
        long criterionId = 0;
        long testId = 0;
        if (setup != Setup.EMPTY_COURSE) {
            Assignment assignment = assignments.save(new Assignment(
                    course.getId(),
                    "Sorting",
                    "d",
                    "JAVA",
                    Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                    null,
                    2000,
                    256,
                    null));
            RubricCriterion criterion =
                    rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 100));
            assignmentId = assignment.getId();
            criterionId = criterion.getId();
            if (setup != Setup.DRAFT_CRITERION_WITHOUT_TESTS) {
                testId = testCases
                        .save(new TestCase(
                                assignment.getId(), criterion.getId(), "t1", "in", "out", TestVisibility.PUBLIC, 1, 1))
                        .getId();
            }
            if (setup == Setup.PUBLISHED) {
                jdbc.update("update assignments set published = true where id = ?", assignment.getId());
            }
        }
        return new World(
                Map.of(
                        Actor.OWNER, owner,
                        Actor.OTHER_TEACHER, other,
                        Actor.ENROLLED_STUDENT, enrolled,
                        Actor.OUTSIDER, outsider,
                        Actor.ADMIN, admin),
                new Ids(course.getId(), assignmentId, criterionId, testId, course.getEnrollCode()));
    }

    /** Everything a Phase 3 write can touch, as plain data, to compare before and after a refused call. */
    private List<Object> snapshot() {
        return List.of(
                jdbc.queryForList("select * from courses order by id"),
                jdbc.queryForList("select * from enrollments order by course_id, student_id"),
                jdbc.queryForList("select * from assignments order by id"),
                jdbc.queryForList("select * from rubric_criteria order by id"),
                jdbc.queryForList("select * from test_cases order by id"));
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

    static Stream<Row> rows() {
        return ENDPOINTS.stream();
    }

    // --- what the status alone does not show -------------------------------------------------

    @ParameterizedTest
    @EnumSource(Actor.class)
    void theCourseListShowsEachCallerOnlyTheirOwnCourses(Actor actor) throws Exception {
        World world = buildWorld(Setup.DRAFT);

        HttpResponse<String> response = get("/courses", world.token(this, actor));

        List<Integer> seen = JsonPath.read(response.body(), "$.items[*].id");
        boolean sees = actor == Actor.OWNER || actor == Actor.ENROLLED_STUDENT || actor == Actor.ADMIN;
        assertThat(seen.contains((int) world.ids().course()))
                .as("%s sees the course in the list", actor)
                .isEqualTo(sees);
    }

    @ParameterizedTest
    @EnumSource(Actor.class)
    void theEnrollCodeIsOnlyForThePeopleWhoRunTheCourse(Actor actor) throws Exception {
        World world = buildWorld(Setup.DRAFT);

        HttpResponse<String> response = get("/courses/" + world.ids().course(), world.token(this, actor));

        if (response.statusCode() == 200) {
            boolean runsCourse = actor == Actor.OWNER || actor == Actor.ADMIN;
            assertThat(response.body().contains("enrollCode"))
                    .as("%s sees the enrollment code", actor)
                    .isEqualTo(runsCourse);
            assertThat(response.body().contains("ABCD2345")).isEqualTo(runsCourse);
        }
    }

    @ParameterizedTest
    @EnumSource(Actor.class)
    void theAssignmentListHidesDraftsFromStudentsOnly(Actor actor) throws Exception {
        World world = buildWorld(Setup.DRAFT);

        HttpResponse<String> response =
                get("/courses/" + world.ids().course() + "/assignments", world.token(this, actor));

        if (response.statusCode() == 200) {
            int count = JsonPath.read(response.body(), "$.totalElements");
            assertThat(count)
                    .as("%s sees %d assignments", actor, count)
                    .isEqualTo(actor == Actor.ENROLLED_STUDENT ? 0 : 1);
        } else {
            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void anEnrolledStudentNeverSeesAHiddenTestsContentOrTheRubric() throws Exception {
        World world = buildWorld(Setup.PUBLISHED);
        jdbc.update(
                "update test_cases set visibility = 'HIDDEN', name = 'SECRET-NAME', input = 'SECRET-IN', expected_output = 'SECRET-OUT'");
        String token = world.token(this, Actor.ENROLLED_STUDENT);

        String tests = get("/assignments/" + world.ids().assignment() + "/tests", token)
                .body();
        String assignment =
                get("/assignments/" + world.ids().assignment(), token).body();
        String list =
                get("/courses/" + world.ids().course() + "/assignments", token).body();

        assertThat(tests).doesNotContain("SECRET-NAME", "SECRET-IN", "SECRET-OUT");
        assertThat(assignment + list).doesNotContain("SECRET");
        assertThat(get("/assignments/" + world.ids().assignment() + "/rubric", token)
                        .statusCode())
                .isEqualTo(403);
    }

    // --- completeness ------------------------------------------------------------------------

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyPhase3EndpointHasARow() {
        Set<Class<?>> phase3 = Set.of(
                CourseController.class, AssignmentController.class, RubricController.class, TestCaseController.class);
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!phase3.contains(handler.getBeanType())) {
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
                .as("Phase 3 endpoints mapped by Spring vs rows of ENDPOINTS in AuthorizationMatrixTests;"
                        + " add a row for each new endpoint")
                .isEqualTo(mapped);
    }

    @Test
    void theMatrixIsComplete() {
        // Five callers per row, and a status for each.
        assertThat(ENDPOINTS).allSatisfy(row -> assertThat(row.expected()).hasSize(Actor.values().length));
    }
}
