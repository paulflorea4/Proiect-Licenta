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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 3.4b: what `POST /assignments/{id}/publish` requires of the rubric. */
class RealHttpPublishRulesTests extends RealHttpTestBase {

    private User owner;
    private User admin;
    private User student;
    private Course course;
    private Assignment assignment;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "d",
                "JAVA",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
    }

    @AfterEach
    void removeWhatReferencesTheRubric() {
        jdbc.update("delete from test_cases");
    }

    private RubricCriterion criterion(int weight, boolean withTest) {
        RubricCriterion c =
                rubricCriteria.save(new RubricCriterion(assignment.getId(), "C" + weight, CriterionType.TESTS, weight));
        if (withTest) {
            jdbc.update(
                    "insert into test_cases (assignment_id, criterion_id, name, input, expected_output, visibility, position) "
                            + "values (?, ?, 't', '1', '1', 'PUBLIC', 1)",
                    assignment.getId(),
                    c.getId());
        }
        return c;
    }

    private HttpResponse<String> publish(User as) throws Exception {
        return send("POST", "/assignments/" + assignment.getId() + "/publish", tokenFor(as), null);
    }

    private HttpResponse<String> unpublish(User as) throws Exception {
        return send("POST", "/assignments/" + assignment.getId() + "/unpublish", tokenFor(as), null);
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private Assignment stored() {
        return assignments.findById(assignment.getId()).orElseThrow();
    }

    private void assertStillADraft(HttpResponse<String> response, String expectedCode) {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo(expectedCode);
        assertThat(stored().isPublished()).isFalse();
        assertThat(stored().getUpdatedAt()).isEqualTo(assignment.getUpdatedAt());
    }

    // --- what is refused ---------------------------------------------------------------------

    @Test
    void anAssignmentWithNoRubricCannotBePublished() throws Exception {
        assertStillADraft(publish(owner), "RUBRIC_WEIGHTS_INVALID");
    }

    @Test
    void weightsBelow100CannotBePublished() throws Exception {
        criterion(99, true);

        assertStillADraft(publish(owner), "RUBRIC_WEIGHTS_INVALID");
    }

    @Test
    void weightsAbove100CannotBePublished() throws Exception {
        criterion(60, true);
        criterion(41, true);

        assertStillADraft(publish(owner), "RUBRIC_WEIGHTS_INVALID");
    }

    @Test
    void aTestsCriterionWithoutATestCannotBePublished() throws Exception {
        criterion(60, true);
        criterion(40, false);

        assertStillADraft(publish(owner), "CRITERION_HAS_NO_TESTS");
    }

    @Test
    void theWeightRuleIsReportedBeforeTheMissingTestsRule() throws Exception {
        criterion(50, false);

        assertStillADraft(publish(owner), "RUBRIC_WEIGHTS_INVALID");
    }

    @Test
    void aTestOfAnotherCriterionDoesNotCountForThisOne() throws Exception {
        criterion(60, true);
        RubricCriterion bare = criterion(40, false);
        assertThat(bare.getId()).isNotNull();

        assertStillADraft(publish(owner), "CRITERION_HAS_NO_TESTS");
    }

    @Test
    void aTestOfAnotherAssignmentsCriterionDoesNotCountEither() throws Exception {
        Assignment other = assignments.save(new Assignment(
                course.getId(), "Other", "d", "JAVA", Instant.now().plusSeconds(3600), null, 1000, 64, null));
        RubricCriterion elsewhere =
                rubricCriteria.save(new RubricCriterion(other.getId(), "E", CriterionType.TESTS, 100));
        jdbc.update(
                "insert into test_cases (assignment_id, criterion_id, name, input, expected_output, visibility, position) "
                        + "values (?, ?, 't', '1', '1', 'PUBLIC', 1)",
                other.getId(),
                elsewhere.getId());
        criterion(100, false);

        assertStillADraft(publish(owner), "CRITERION_HAS_NO_TESTS");
    }

    // --- what is accepted --------------------------------------------------------------------

    @Test
    void weightsOfExactly100WithATestInEveryCriterionArePublished() throws Exception {
        criterion(30, true);
        criterion(70, true);

        HttpResponse<String> response = publish(owner);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.published")).isTrue();
        assertThat(stored().isPublished()).isTrue();
    }

    @Test
    void oneCriterionWorth100IsPublishable() throws Exception {
        criterion(100, true);

        assertThat(publish(owner).statusCode()).isEqualTo(200);
    }

    @Test
    void anAdminIsHeldToTheSameRules() throws Exception {
        assertThat(publish(admin).statusCode()).isEqualTo(409);

        criterion(100, true);

        assertThat(publish(admin).statusCode()).isEqualTo(200);
    }

    @Test
    void fixingTheRubricThroughTheApiLetsThePublishThrough() throws Exception {
        String token = tokenFor(owner);
        String path = "/assignments/" + assignment.getId() + "/rubric";
        assertThat(publish(owner).statusCode()).isEqualTo(409);

        HttpResponse<String> added = post(path, "{\"name\":\"Tests\",\"type\":\"TESTS\",\"weight\":100}", token);
        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(publish(owner).statusCode()).isEqualTo(409);
        assertThat(code(publish(owner))).isEqualTo("CRITERION_HAS_NO_TESTS");

        jdbc.update(
                "insert into test_cases (assignment_id, criterion_id, name, input, expected_output, visibility, position) "
                        + "values (?, ?, 't', '1', '1', 'PUBLIC', 1)",
                assignment.getId(),
                JsonPath.<Integer>read(added.body(), "$.id").longValue());

        assertThat(publish(owner).statusCode()).isEqualTo(200);
    }

    // --- only a change to published is checked -----------------------------------------------

    @Test
    void publishingAnAlreadyPublishedAssignmentChecksNothing() throws Exception {
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());

        // No rubric at all: it is a no-op, not a new publication.
        assertThat(publish(owner).statusCode()).isEqualTo(200);
        assertThat(stored().isPublished()).isTrue();
    }

    @Test
    void hidingNeverChecksTheRubric() throws Exception {
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());

        assertThat(unpublish(owner).statusCode()).isEqualTo(200);
        assertThat(stored().isPublished()).isFalse();
    }

    @Test
    void aStudentIsStillRefusedByRoleWhateverTheRubric() throws Exception {
        criterion(100, true);

        assertThat(publish(student).statusCode()).isEqualTo(403);
        assertThat(stored().isPublished()).isFalse();
    }

    @Test
    void aRefusedPublicationLeavesTheDraftInvisibleToStudents() throws Exception {
        publish(owner);

        assertThat(get("/assignments/" + assignment.getId(), tokenFor(student)).statusCode())
                .isEqualTo(404);
    }
}
