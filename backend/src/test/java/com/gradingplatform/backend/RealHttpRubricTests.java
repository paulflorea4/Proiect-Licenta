package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.4a: `GET/POST/PUT/DELETE /assignments/{id}/rubric`. */
class RealHttpRubricTests extends RealHttpTestBase {

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private Course course;
    private Assignment assignment;
    private Assignment otherAssignment;

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
    }

    /** Rows that reference criteria and assignments go before the base class deletes those. */
    @AfterEach
    void removeWhatReferencesTheRubric() {
        jdbc.update("delete from submissions");
        jdbc.update("delete from test_cases");
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

    private static String body(String name, String type, Object weight) {
        return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"weight\":" + weight + "}";
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private String rubricPath(Assignment of) {
        return "/assignments/" + of.getId() + "/rubric";
    }

    private HttpResponse<String> listRubric(User as, Assignment of) throws Exception {
        return get(rubricPath(of), as == null ? null : tokenFor(as));
    }

    private HttpResponse<String> add(User as, Assignment to, String json) throws Exception {
        return post(rubricPath(to), json, as == null ? null : tokenFor(as));
    }

    private HttpResponse<String> replace(User as, Assignment of, long criterionId, String json) throws Exception {
        return send("PUT", rubricPath(of) + "/" + criterionId, as == null ? null : tokenFor(as), json);
    }

    private HttpResponse<String> remove(User as, Assignment of, long criterionId) throws Exception {
        return send("DELETE", rubricPath(of) + "/" + criterionId, as == null ? null : tokenFor(as), null);
    }

    private RubricCriterion criterion(Assignment of, String name, int weight) {
        return rubricCriteria.save(new RubricCriterion(of.getId(), name, CriterionType.TESTS, weight));
    }

    private void submit(Assignment to) {
        jdbc.update(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no) "
                        + "values (?, ?, 'JAVA', 'x', 1)",
                to.getId(),
                student.getId());
    }

    private void addTestTo(RubricCriterion c) {
        jdbc.update(
                "insert into test_cases (assignment_id, criterion_id, name, input, expected_output, visibility, position) "
                        + "values (?, ?, 't1', '1', '1', 'PUBLIC', 1)",
                c.getAssignmentId(),
                c.getId());
    }

    // --- create ------------------------------------------------------------------------------

    @Test
    void theOwnerAddsATestsCriterion() throws Exception {
        HttpResponse<String> response = add(owner, assignment, body("Unit tests", "TESTS", 70));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.name")).isEqualTo("Unit tests");
        assertThat(JsonPath.<String>read(response.body(), "$.type")).isEqualTo("TESTS");
        assertThat(JsonPath.<Integer>read(response.body(), "$.weight")).isEqualTo(70);
        assertThat(JsonPath.<Integer>read(response.body(), "$.assignmentId"))
                .isEqualTo(assignment.getId().intValue());
        List<RubricCriterion> stored = rubricCriteria.findByAssignmentIdOrderByIdAsc(assignment.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getId())
                .isEqualTo(JsonPath.<Integer>read(response.body(), "$.id").longValue());
        assertThat(stored.get(0).getType()).isEqualTo(CriterionType.TESTS);
    }

    @Test
    void anAdminMayAddOne() throws Exception {
        assertThat(add(admin, assignment, body("T", "TESTS", 100)).statusCode()).isEqualTo(201);
    }

    @Test
    void theNameIsTrimmedAndAnIdInTheBodyIsIgnored() throws Exception {
        HttpResponse<String> response = add(
                owner,
                assignment,
                "{\"id\":999,\"assignmentId\":999,\"name\":\"  Tests  \",\"type\":\"TESTS\",\"weight\":10}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.name")).isEqualTo("Tests");
        assertThat(JsonPath.<Integer>read(response.body(), "$.assignmentId"))
                .isEqualTo(assignment.getId().intValue());
        assertThat(rubricCriteria.findAll()).hasSize(1);
    }

    @Test
    void weightsMayAddUpToMoreOrLessThan100WhileItIsADraft() throws Exception {
        assertThat(add(owner, assignment, body("A", "TESTS", 80)).statusCode()).isEqualTo(201);
        assertThat(add(owner, assignment, body("B", "TESTS", 80)).statusCode()).isEqualTo(201);

        assertThat(JsonPath.<Integer>read(listRubric(owner, assignment).body(), "$.totalWeight"))
                .isEqualTo(160);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATIC_ANALYSIS", "OPEN_ANSWER", "MANUAL"})
    void aTypeNoPhaseHasEnabledYetIsRefusedClearly(String type) throws Exception {
        HttpResponse<String> response = add(owner, assignment, body("X", type, 10));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("CRITERION_TYPE_NOT_AVAILABLE");
        assertThat(rubricCriteria.findAll()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tests", "Tests", "STYLE", ""})
    void anUnknownTypeIsA400(String type) throws Exception {
        HttpResponse<String> response = add(owner, assignment, body("X", type, 10));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(rubricCriteria.findAll()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "101", "1000", "null", "\"ten\""})
    void aWeightOutsideOneTo100IsA400(String weight) throws Exception {
        HttpResponse<String> response = add(owner, assignment, body("X", "TESTS", weight));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(rubricCriteria.findAll()).isEmpty();
    }

    @Test
    void theLimitsOfTheWeightRangeAreAccepted() throws Exception {
        assertThat(add(owner, assignment, body("Low", "TESTS", 1)).statusCode()).isEqualTo(201);
        assertThat(add(owner, assignment, body("High", "TESTS", 100)).statusCode())
                .isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankNameIsA400(String name) throws Exception {
        HttpResponse<String> response = add(owner, assignment, body(name, "TESTS", 10));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void aNameOver255CharactersIsA400AndExactly255IsFine() throws Exception {
        assertThat(add(owner, assignment, body("x".repeat(256), "TESTS", 10)).statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, body("x".repeat(255), "TESTS", 10)).statusCode())
                .isEqualTo(201);
    }

    @Test
    void missingFieldsAreA400() throws Exception {
        assertThat(add(owner, assignment, "{\"type\":\"TESTS\",\"weight\":10}").statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, "{\"name\":\"X\",\"weight\":10}").statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, "{\"name\":\"X\",\"type\":\"TESTS\"}").statusCode())
                .isEqualTo(400);
        assertThat(add(owner, assignment, "not json").statusCode()).isEqualTo(400);
    }

    // --- list --------------------------------------------------------------------------------

    @Test
    void theListHoldsOnlyThisAssignmentsCriteriaInCreationOrderWithTheirTotal() throws Exception {
        RubricCriterion first = criterion(assignment, "Second to be named", 30);
        RubricCriterion second = criterion(assignment, "A", 50);
        criterion(otherAssignment, "Elsewhere", 99);

        HttpResponse<String> response = listRubric(owner, assignment);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(response.body(), "$.criteria[*].id"))
                .containsExactly(first.getId().intValue(), second.getId().intValue());
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalWeight")).isEqualTo(80);
    }

    @Test
    void anEmptyRubricIsAnEmptyListWithTotalZero() throws Exception {
        HttpResponse<String> response = listRubric(owner, assignment);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.criteria")).isEmpty();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalWeight")).isZero();
    }

    @Test
    void anAdminMayRead() throws Exception {
        assertThat(listRubric(admin, assignment).statusCode()).isEqualTo(200);
    }

    // --- update ------------------------------------------------------------------------------

    @Test
    void theOwnerChangesNameAndWeight() throws Exception {
        RubricCriterion c = criterion(assignment, "Old", 10);

        HttpResponse<String> response = replace(owner, assignment, c.getId(), body("New", "TESTS", 60));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.name")).isEqualTo("New");
        RubricCriterion stored = rubricCriteria.findById(c.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("New");
        assertThat(stored.getWeight()).isEqualTo(60);
        assertThat(stored.getAssignmentId()).isEqualTo(assignment.getId());
    }

    @Test
    void anUpdateToAnUnavailableTypeIsRefusedAndChangesNothing() throws Exception {
        RubricCriterion c = criterion(assignment, "Old", 10);

        HttpResponse<String> response = replace(owner, assignment, c.getId(), body("New", "MANUAL", 60));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("CRITERION_TYPE_NOT_AVAILABLE");
        assertThat(rubricCriteria.findById(c.getId()).orElseThrow().getName()).isEqualTo("Old");
    }

    @Test
    void aCriterionOfAnotherAssignmentOrAMissingOneIsTheSame404() throws Exception {
        RubricCriterion elsewhere = criterion(otherAssignment, "Elsewhere", 10);

        HttpResponse<String> wrongAssignment = replace(owner, assignment, elsewhere.getId(), body("X", "TESTS", 5));
        HttpResponse<String> missing = replace(owner, assignment, 987654321L, body("X", "TESTS", 5));

        assertThat(wrongAssignment.statusCode()).isEqualTo(404);
        assertThat(code(wrongAssignment)).isEqualTo("CRITERION_NOT_FOUND");
        assertThat(wrongAssignment.body()).isEqualTo(missing.body());
        assertThat(rubricCriteria.findById(elsewhere.getId()).orElseThrow().getName())
                .isEqualTo("Elsewhere");
        assertThat(remove(owner, assignment, elsewhere.getId()).statusCode()).isEqualTo(404);
        assertThat(rubricCriteria.findById(elsewhere.getId())).isPresent();
    }

    // --- delete ------------------------------------------------------------------------------

    @Test
    void theOwnerDeletesACriterion() throws Exception {
        RubricCriterion keep = criterion(assignment, "Keep", 20);
        RubricCriterion drop = criterion(assignment, "Drop", 80);

        HttpResponse<String> response = remove(owner, assignment, drop.getId());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(rubricCriteria.findAll()).extracting(RubricCriterion::getId).containsExactly(keep.getId());
        assertThat(remove(owner, assignment, drop.getId()).statusCode()).isEqualTo(404);
    }

    @Test
    void aCriterionThatHasTestsCannotBeDeletedAndTheTestsStay() throws Exception {
        RubricCriterion c = criterion(assignment, "Tests", 100);
        addTestTo(c);

        HttpResponse<String> response = remove(owner, assignment, c.getId());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("CRITERION_HAS_TESTS");
        assertThat(rubricCriteria.findById(c.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from test_cases", Integer.class))
                .isEqualTo(1);
    }

    // --- locked after the first submission ---------------------------------------------------

    @Test
    void afterASubmissionNoCriterionCanBeAddedChangedOrDeleted() throws Exception {
        RubricCriterion c = criterion(assignment, "Tests", 100);
        submit(assignment);

        HttpResponse<String> added = add(owner, assignment, body("More", "TESTS", 10));
        HttpResponse<String> changed = replace(owner, assignment, c.getId(), body("Tests", "TESTS", 50));
        HttpResponse<String> deleted = remove(owner, assignment, c.getId());

        for (HttpResponse<String> response : List.of(added, changed, deleted)) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(code(response)).isEqualTo("RUBRIC_LOCKED");
        }
        List<RubricCriterion> stored = rubricCriteria.findByAssignmentIdOrderByIdAsc(assignment.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getWeight()).isEqualTo(100);
        assertThat(stored.get(0).getName()).isEqualTo("Tests");
    }

    @Test
    void aSubmissionToAnotherAssignmentLocksNothingHere() throws Exception {
        RubricCriterion c = criterion(assignment, "Tests", 100);
        submit(otherAssignment);

        assertThat(replace(owner, assignment, c.getId(), body("Tests", "TESTS", 50))
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void theRubricCanStillBeReadAfterASubmission() throws Exception {
        criterion(assignment, "Tests", 100);
        submit(assignment);

        assertThat(listRubric(owner, assignment).statusCode()).isEqualTo(200);
    }

    // --- who may do what ---------------------------------------------------------------------

    @Test
    void anotherTeacherGetsTheSame404AsForAMissingAssignmentOnEveryEndpoint() throws Exception {
        RubricCriterion c = criterion(assignment, "Tests", 100);
        HttpResponse<String> missing = get("/assignments/987654321/rubric", tokenFor(otherTeacher));

        List<HttpResponse<String>> responses = List.of(
                listRubric(otherTeacher, assignment),
                add(otherTeacher, assignment, body("X", "TESTS", 10)),
                replace(otherTeacher, assignment, c.getId(), body("X", "TESTS", 10)),
                remove(otherTeacher, assignment, c.getId()));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(code(response)).isEqualTo("ASSIGNMENT_NOT_FOUND");
            assertThat(response.body()).isEqualTo(missing.body());
        }
        assertThat(rubricCriteria.findAll()).hasSize(1);
        assertThat(rubricCriteria.findAll().get(0).getName()).isEqualTo("Tests");
    }

    @Test
    void studentsAreRefusedEvenForTheirOwnPublishedCourse() throws Exception {
        RubricCriterion c = criterion(assignment, "Tests", 100);
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());

        assertThat(listRubric(student, assignment).statusCode()).isEqualTo(403);
        assertThat(add(student, assignment, body("X", "TESTS", 10)).statusCode())
                .isEqualTo(403);
        assertThat(replace(student, assignment, c.getId(), body("X", "TESTS", 10))
                        .statusCode())
                .isEqualTo(403);
        assertThat(remove(student, assignment, c.getId()).statusCode()).isEqualTo(403);
        assertThat(rubricCriteria.findAll()).hasSize(1);
    }

    @Test
    void anonymousCallersAreUnauthenticated() throws Exception {
        RubricCriterion c = criterion(assignment, "Tests", 100);

        assertThat(listRubric(null, assignment).statusCode()).isEqualTo(401);
        assertThat(add(null, assignment, body("X", "TESTS", 10)).statusCode()).isEqualTo(401);
        assertThat(replace(null, assignment, c.getId(), body("X", "TESTS", 10)).statusCode())
                .isEqualTo(401);
        assertThat(remove(null, assignment, c.getId()).statusCode()).isEqualTo(401);
    }

    @Test
    void deletingTheAssignmentTakesItsRubricWithIt() throws Exception {
        criterion(assignment, "Tests", 100);
        Assignment survivor = otherAssignment;
        RubricCriterion kept = criterion(survivor, "Kept", 100);

        assertThat(send("DELETE", "/assignments/" + assignment.getId(), tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(204);

        assertThat(rubricCriteria.findAll()).extracting(RubricCriterion::getId).containsExactly(kept.getId());
    }
}
