package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.5a: `GET/POST/PUT/DELETE /assignments/{id}/tests`, and the "draft only" rule for the rubric. */
class RealHttpTestCaseTests extends RealHttpTestBase {

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private Course course;
    private Assignment assignment;
    private Assignment otherAssignment;
    private RubricCriterion criterion;
    private RubricCriterion otherCriterion;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = newAssignment("Sorting");
        otherAssignment = newAssignment("Searching");
        criterion = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 100));
        otherCriterion = rubricCriteria.save(
                new RubricCriterion(otherAssignment.getId(), "Elsewhere", CriterionType.TESTS, 100));
    }

    @AfterEach
    void removeSubmissions() {
        jdbc.update("delete from submissions");
    }

    private Assignment newAssignment(String title) {
        return assignments.save(new Assignment(
                course.getId(),
                title,
                "d",
                "JAVA",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
    }

    // --- helpers -----------------------------------------------------------------------------

    private static String quoted(String text) {
        return "\""
                + text.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t") + "\"";
    }

    /** A valid body for the given criterion; each test overrides what it is about. */
    private static String body(Long criterionId, String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("criterionId", String.valueOf(criterionId));
        fields.put("name", "\"sorted input\"");
        fields.put("input", "\"3\\n1 2 3\\n\"");
        fields.put("expectedOutput", "\"1 2 3\\n\"");
        fields.put("visibility", "\"PUBLIC\"");
        fields.put("weight", "2");
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        StringBuilder json = new StringBuilder("{");
        fields.forEach((k, v) -> {
            if (v != null) {
                json.append(json.length() > 1 ? "," : "")
                        .append('"')
                        .append(k)
                        .append("\":")
                        .append(v);
            }
        });
        return json.append('}').toString();
    }

    private String body(String... overrides) {
        return body(criterion.getId(), overrides);
    }

    private String path(Assignment of) {
        return "/assignments/" + of.getId() + "/tests";
    }

    private String tokenOf(User as) {
        return as == null ? null : tokenFor(as);
    }

    private HttpResponse<String> listTests(User as, Assignment of) throws Exception {
        return get(path(of), tokenOf(as));
    }

    private HttpResponse<String> add(User as, Assignment to, String json) throws Exception {
        return post(path(to), json, tokenOf(as));
    }

    private HttpResponse<String> replace(User as, Assignment of, long testId, String json) throws Exception {
        return send("PUT", path(of) + "/" + testId, tokenOf(as), json);
    }

    private HttpResponse<String> remove(User as, Assignment of, long testId) throws Exception {
        return send("DELETE", path(of) + "/" + testId, tokenOf(as), null);
    }

    private TestCase test(Assignment of, RubricCriterion in, String name, int position) {
        return testCases.save(
                new TestCase(of.getId(), in.getId(), name, "in", "out", TestVisibility.PUBLIC, 1, position));
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private void submit(Assignment to) {
        jdbc.update(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no) "
                        + "values (?, ?, 'JAVA', 'x', 1)",
                to.getId(),
                student.getId());
    }

    private void publishBySql(Assignment a) {
        jdbc.update("update assignments set published = true where id = ?", a.getId());
    }

    // --- create ------------------------------------------------------------------------------

    @Test
    void theOwnerAddsATest() throws Exception {
        HttpResponse<String> response = add(owner, assignment, body());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.name")).isEqualTo("sorted input");
        assertThat(JsonPath.<String>read(response.body(), "$.input")).isEqualTo("3\n1 2 3\n");
        assertThat(JsonPath.<String>read(response.body(), "$.expectedOutput")).isEqualTo("1 2 3\n");
        assertThat(JsonPath.<String>read(response.body(), "$.visibility")).isEqualTo("PUBLIC");
        assertThat(JsonPath.<Integer>read(response.body(), "$.weight")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(response.body(), "$.position")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(response.body(), "$.criterionId"))
                .isEqualTo(criterion.getId().intValue());
        assertThat(JsonPath.<Integer>read(response.body(), "$.assignmentId"))
                .isEqualTo(assignment.getId().intValue());
        List<TestCase> stored = testCases.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getInput()).isEqualTo("3\n1 2 3\n");
    }

    @Test
    void anAdminMayAddOne() throws Exception {
        assertThat(add(admin, assignment, body()).statusCode()).isEqualTo(201);
    }

    @Test
    void aHiddenTestIsStoredAsHidden() throws Exception {
        HttpResponse<String> response = add(owner, assignment, body("visibility", "\"HIDDEN\""));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(testCases.findAll().get(0).getVisibility()).isEqualTo(TestVisibility.HIDDEN);
    }

    @Test
    void inputAndExpectedOutputAreKeptExactlyAndMayBeEmpty() throws Exception {
        String padded = "  keep  \n\n\t";

        HttpResponse<String> response =
                add(owner, assignment, body("input", quoted(padded), "expectedOutput", quoted("")));

        assertThat(response.statusCode()).isEqualTo(201);
        TestCase stored = testCases.findAll().get(0);
        assertThat(stored.getInput()).isEqualTo(padded);
        assertThat(stored.getExpectedOutput()).isEmpty();
    }

    @Test
    void theNameIsTrimmedAndIdsInTheBodyAreIgnored() throws Exception {
        HttpResponse<String> response = add(
                owner,
                assignment,
                body("name", "\"  t  \"", "id", "999", "assignmentId", String.valueOf(otherAssignment.getId())));

        assertThat(response.statusCode()).isEqualTo(201);
        TestCase stored = testCases.findAll().get(0);
        assertThat(stored.getName()).isEqualTo("t");
        assertThat(stored.getAssignmentId()).isEqualTo(assignment.getId());
    }

    @Test
    void testsAreAppendedInOrderUnlessAPositionIsGiven() throws Exception {
        add(owner, assignment, body("name", "\"a\""));
        add(owner, assignment, body("name", "\"b\""));
        add(owner, assignment, body("name", "\"c\"", "position", "10"));
        HttpResponse<String> next = add(owner, assignment, body("name", "\"d\""));

        assertThat(JsonPath.<Integer>read(next.body(), "$.position")).isEqualTo(11);
        assertThat(JsonPath.<List<String>>read(listTests(owner, assignment).body(), "$.tests[*].name"))
                .containsExactly("a", "b", "c", "d");
    }

    @Test
    void positionsOfOtherAssignmentsDoNotCount() throws Exception {
        test(otherAssignment, otherCriterion, "elsewhere", 50);

        HttpResponse<String> response = add(owner, assignment, body());

        assertThat(JsonPath.<Integer>read(response.body(), "$.position")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "null", "\"two\""})
    void aWeightOutsideOneTo100IsA400(String weight) throws Exception {
        assertThat(add(owner, assignment, body("weight", weight)).statusCode()).isEqualTo(400);
        assertThat(testCases.findAll()).isEmpty();
    }

    @Test
    void theLimitsOfTheWeightAreAccepted() throws Exception {
        assertThat(add(owner, assignment, body("weight", "1")).statusCode()).isEqualTo(201);
        assertThat(add(owner, assignment, body("weight", "100")).statusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null"})
    void aBlankOrMissingNameIsA400(String name) throws Exception {
        assertThat(add(owner, assignment, body("name", name)).statusCode()).isEqualTo(400);
    }

    @Test
    void aNameOver255CharactersIsA400() throws Exception {
        assertThat(add(owner, assignment, body("name", quoted("x".repeat(256)))).statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, body("name", quoted("x".repeat(255)))).statusCode())
                .isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"SECRET\"", "\"public\"", "\"\"", "null", "[]"})
    void anUnknownOrMissingVisibilityIsA400(String visibility) throws Exception {
        assertThat(add(owner, assignment, body("visibility", visibility)).statusCode())
                .isEqualTo(400);
    }

    @Test
    void missingInputOrOutputIsA400ButEmptyIsNot() throws Exception {
        assertThat(add(owner, assignment, body("input", null)).statusCode()).isEqualTo(400);
        assertThat(add(owner, assignment, body("expectedOutput", null)).statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, body("input", "\"\"", "expectedOutput", "\"\""))
                        .statusCode())
                .isEqualTo(201);
    }

    @Test
    void textOverTheCapIsA400() throws Exception {
        assertThat(add(owner, assignment, body("input", quoted("x".repeat(100_001))))
                        .statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, body("expectedOutput", quoted("x".repeat(100_001))))
                        .statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, body("input", quoted("x".repeat(100_000))))
                        .statusCode())
                .isEqualTo(201);
    }

    @Test
    void aNegativePositionIsA400() throws Exception {
        assertThat(add(owner, assignment, body("position", "-1")).statusCode()).isEqualTo(400);
    }

    @Test
    void aMissingCriterionIdAndMalformedBodiesAreA400() throws Exception {
        assertThat(add(owner, assignment, body("criterionId", null)).statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, "not json").statusCode()).isEqualTo(400);
        assertThat(testCases.findAll()).isEmpty();
    }

    @Test
    void aCriterionThatIsMissingOrAnotherAssignmentsIsTheSameError() throws Exception {
        HttpResponse<String> elsewhere = add(owner, assignment, body(otherCriterion.getId()));
        HttpResponse<String> missing = add(owner, assignment, body(987654321L));

        assertThat(elsewhere.statusCode()).isEqualTo(400);
        assertThat(code(elsewhere)).isEqualTo("TEST_CRITERION_INVALID");
        assertThat(elsewhere.body()).isEqualTo(missing.body());
        assertThat(testCases.findAll()).isEmpty();
    }

    // --- list --------------------------------------------------------------------------------

    @Test
    void theListIsInRunOrderAndHoldsOnlyThisAssignmentsTestsInFull() throws Exception {
        test(assignment, criterion, "second", 2);
        test(assignment, criterion, "first", 1);
        testCases.save(new TestCase(
                assignment.getId(),
                criterion.getId(),
                "secret",
                "hidden in",
                "hidden out",
                TestVisibility.HIDDEN,
                1,
                3));
        test(otherAssignment, otherCriterion, "elsewhere", 1);

        HttpResponse<String> response = listTests(owner, assignment);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.tests[*].name"))
                .containsExactly("first", "second", "secret");
        assertThat(JsonPath.<String>read(response.body(), "$.tests[2].input")).isEqualTo("hidden in");
        assertThat(JsonPath.<String>read(response.body(), "$.tests[2].expectedOutput"))
                .isEqualTo("hidden out");
    }

    @Test
    void anEmptyListIsEmptyAndAnAdminMayRead() throws Exception {
        HttpResponse<String> response = listTests(admin, assignment);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.tests")).isEmpty();
    }

    @Test
    void theListStaysReadableOnAPublishedAssignmentWithSubmissions() throws Exception {
        test(assignment, criterion, "t", 1);
        publishBySql(assignment);
        submit(assignment);

        assertThat(listTests(owner, assignment).statusCode()).isEqualTo(200);
    }

    // --- update ------------------------------------------------------------------------------

    @Test
    void theOwnerReplacesATest() throws Exception {
        TestCase t = test(assignment, criterion, "old", 4);
        RubricCriterion second =
                rubricCriteria.save(new RubricCriterion(assignment.getId(), "More", CriterionType.TESTS, 10));

        HttpResponse<String> response = replace(
                owner,
                assignment,
                t.getId(),
                body(second.getId(), "name", "\"new\"", "visibility", "\"HIDDEN\"", "weight", "7", "position", "9"));

        assertThat(response.statusCode()).isEqualTo(200);
        TestCase stored = testCases.findById(t.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("new");
        assertThat(stored.getVisibility()).isEqualTo(TestVisibility.HIDDEN);
        assertThat(stored.getWeight()).isEqualTo(7);
        assertThat(stored.getPosition()).isEqualTo(9);
        assertThat(stored.getCriterionId()).isEqualTo(second.getId());
        assertThat(stored.getAssignmentId()).isEqualTo(assignment.getId());
    }

    @Test
    void aPositionLeftOutKeepsTheCurrentOne() throws Exception {
        TestCase t = test(assignment, criterion, "old", 4);

        replace(owner, assignment, t.getId(), body("name", "\"new\""));

        assertThat(testCases.findById(t.getId()).orElseThrow().getPosition()).isEqualTo(4);
    }

    @Test
    void updatingToAnInvalidCriterionIsRefusedAndChangesNothing() throws Exception {
        TestCase t = test(assignment, criterion, "old", 1);

        HttpResponse<String> response =
                replace(owner, assignment, t.getId(), body(otherCriterion.getId(), "name", "\"new\""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("TEST_CRITERION_INVALID");
        assertThat(testCases.findById(t.getId()).orElseThrow().getName()).isEqualTo("old");
    }

    @Test
    void aTestOfAnotherAssignmentOrAMissingOneIsTheSame404() throws Exception {
        TestCase elsewhere = test(otherAssignment, otherCriterion, "elsewhere", 1);

        HttpResponse<String> wrong = replace(owner, assignment, elsewhere.getId(), body());
        HttpResponse<String> missing = replace(owner, assignment, 987654321L, body());

        assertThat(wrong.statusCode()).isEqualTo(404);
        assertThat(code(wrong)).isEqualTo("TEST_CASE_NOT_FOUND");
        assertThat(wrong.body()).isEqualTo(missing.body());
        assertThat(remove(owner, assignment, elsewhere.getId()).statusCode()).isEqualTo(404);
        assertThat(testCases.findById(elsewhere.getId())).isPresent();
        assertThat(testCases.findById(elsewhere.getId()).orElseThrow().getName())
                .isEqualTo("elsewhere");
    }

    // --- delete ------------------------------------------------------------------------------

    @Test
    void theOwnerDeletesATest() throws Exception {
        TestCase keep = test(assignment, criterion, "keep", 1);
        TestCase drop = test(assignment, criterion, "drop", 2);

        HttpResponse<String> response = remove(owner, assignment, drop.getId());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(testCases.findAll()).extracting(TestCase::getId).containsExactly(keep.getId());
        assertThat(remove(owner, assignment, drop.getId()).statusCode()).isEqualTo(404);
    }

    @Test
    void aCriterionCanBeDeletedOnceItsTestsAreGone() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);
        String rubric = "/assignments/" + assignment.getId() + "/rubric/" + criterion.getId();

        HttpResponse<String> blocked = send("DELETE", rubric, tokenFor(owner), null);
        remove(owner, assignment, t.getId());
        HttpResponse<String> allowed = send("DELETE", rubric, tokenFor(owner), null);

        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("CRITERION_HAS_TESTS");
        assertThat(allowed.statusCode()).isEqualTo(204);
    }

    // --- locked: submissions, and published --------------------------------------------------

    @Test
    void afterASubmissionNoTestCanBeAddedChangedOrDeleted() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);
        submit(assignment);

        List<HttpResponse<String>> responses = List.of(
                add(owner, assignment, body()),
                replace(owner, assignment, t.getId(), body("name", "\"new\"")),
                remove(owner, assignment, t.getId()));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(code(response)).isEqualTo("TESTS_LOCKED");
        }
        assertThat(testCases.findAll()).hasSize(1);
        assertThat(testCases.findAll().get(0).getName()).isEqualTo("t");
    }

    @Test
    void aSubmissionToAnotherAssignmentLocksNothingHere() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);
        submit(otherAssignment);

        assertThat(replace(owner, assignment, t.getId(), body("name", "\"new\""))
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void aPublishedAssignmentsTestsCannotBeChanged() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);
        publishBySql(assignment);

        List<HttpResponse<String>> responses = List.of(
                add(owner, assignment, body()),
                replace(owner, assignment, t.getId(), body("name", "\"new\"")),
                remove(owner, assignment, t.getId()));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(code(response)).isEqualTo("ASSIGNMENT_PUBLISHED");
        }
        assertThat(testCases.findAll()).hasSize(1);
        assertThat(testCases.findAll().get(0).getName()).isEqualTo("t");
    }

    @Test
    void aPublishedAssignmentsRubricCannotBeChangedEither() throws Exception {
        publishBySql(assignment);
        String rubric = "/assignments/" + assignment.getId() + "/rubric";
        String criterionBody = "{\"name\":\"X\",\"type\":\"TESTS\",\"weight\":10}";

        List<HttpResponse<String>> responses = List.of(
                post(rubric, criterionBody, tokenFor(owner)),
                send("PUT", rubric + "/" + criterion.getId(), tokenFor(owner), criterionBody),
                send("DELETE", rubric + "/" + criterion.getId(), tokenFor(owner), null));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(code(response)).isEqualTo("ASSIGNMENT_PUBLISHED");
        }
        assertThat(rubricCriteria.findById(criterion.getId()).orElseThrow().getName())
                .isEqualTo("Tests");
        assertThat(get(rubric, tokenFor(owner)).statusCode()).isEqualTo(200);
    }

    @Test
    void withSubmissionsAndPublishedTheSubmissionsRuleIsTheOneReported() throws Exception {
        publishBySql(assignment);
        submit(assignment);

        HttpResponse<String> test = add(owner, assignment, body());
        HttpResponse<String> rubric = post(
                "/assignments/" + assignment.getId() + "/rubric",
                "{\"name\":\"X\",\"type\":\"TESTS\",\"weight\":10}",
                tokenFor(owner));

        assertThat(code(test)).isEqualTo("TESTS_LOCKED");
        assertThat(code(rubric)).isEqualTo("RUBRIC_LOCKED");
    }

    @Test
    void unpublishingMakesTheDraftEditableAgain() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);
        publishBySql(assignment);
        assertThat(replace(owner, assignment, t.getId(), body("name", "\"new\""))
                        .statusCode())
                .isEqualTo(409);

        assertThat(send("POST", "/assignments/" + assignment.getId() + "/unpublish", tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(200);

        assertThat(replace(owner, assignment, t.getId(), body("name", "\"new\""))
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void theWholeFlowDraftThenPublishThenLocked() throws Exception {
        String ownerToken = tokenFor(owner);
        assertThat(add(owner, assignment, body("weight", "1")).statusCode()).isEqualTo(201);
        assertThat(send("POST", "/assignments/" + assignment.getId() + "/publish", ownerToken, null)
                        .statusCode())
                .isEqualTo(200);

        HttpResponse<String> late = add(owner, assignment, body());

        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("ASSIGNMENT_PUBLISHED");
        assertThat(testCases.findAll()).hasSize(1);
    }

    // --- who may do what ---------------------------------------------------------------------

    @Test
    void anotherTeacherGetsTheSame404AsForAMissingAssignmentOnEveryEndpoint() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);
        HttpResponse<String> missing = get("/assignments/987654321/tests", tokenFor(otherTeacher));

        List<HttpResponse<String>> responses = List.of(
                listTests(otherTeacher, assignment),
                add(otherTeacher, assignment, body()),
                replace(otherTeacher, assignment, t.getId(), body()),
                remove(otherTeacher, assignment, t.getId()));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(code(response)).isEqualTo("ASSIGNMENT_NOT_FOUND");
            assertThat(response.body()).isEqualTo(missing.body());
        }
        assertThat(testCases.findAll()).hasSize(1);
        assertThat(testCases.findAll().get(0).getName()).isEqualTo("t");
    }

    @Test
    void studentsAreRefusedOnEveryEndpointEvenWhenPublishedAndEnrolled() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);
        publishBySql(assignment);

        assertThat(listTests(student, assignment).statusCode()).isEqualTo(403);
        assertThat(add(student, assignment, body()).statusCode()).isEqualTo(403);
        assertThat(replace(student, assignment, t.getId(), body()).statusCode()).isEqualTo(403);
        assertThat(remove(student, assignment, t.getId()).statusCode()).isEqualTo(403);
        assertThat(testCases.findAll()).hasSize(1);
    }

    @Test
    void anonymousCallersAreUnauthenticated() throws Exception {
        TestCase t = test(assignment, criterion, "t", 1);

        assertThat(listTests(null, assignment).statusCode()).isEqualTo(401);
        assertThat(add(null, assignment, body()).statusCode()).isEqualTo(401);
        assertThat(replace(null, assignment, t.getId(), body()).statusCode()).isEqualTo(401);
        assertThat(remove(null, assignment, t.getId()).statusCode()).isEqualTo(401);
    }
}
