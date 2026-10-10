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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 3.5b: what a student gets from `GET /assignments/{id}/tests`, asserted on the raw JSON. */
class RealHttpStudentTestViewTests extends RealHttpTestBase {

    private static final String SECRET_NAME = "NAME-OF-THE-HIDDEN-TEST";
    private static final String SECRET_INPUT = "INPUT-OF-THE-HIDDEN-TEST";
    private static final String SECRET_OUTPUT = "OUTPUT-OF-THE-HIDDEN-TEST";

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private User outsider;
    private Course course;
    private Assignment assignment;
    private RubricCriterion criterion;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
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
        criterion = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 100));
        testCases.save(new TestCase(
                assignment.getId(), criterion.getId(), "visible one", "1 2", "3", TestVisibility.PUBLIC, 2, 1));
        testCases.save(new TestCase(
                assignment.getId(),
                criterion.getId(),
                SECRET_NAME,
                SECRET_INPUT,
                SECRET_OUTPUT,
                TestVisibility.HIDDEN,
                5,
                2));
        testCases.save(new TestCase(
                assignment.getId(), criterion.getId(), "visible two", "4 5", "9", TestVisibility.PUBLIC, 1, 3));
        testCases.save(new TestCase(
                assignment.getId(),
                criterion.getId(),
                SECRET_NAME + "-2",
                SECRET_INPUT + "-2",
                SECRET_OUTPUT + "-2",
                TestVisibility.HIDDEN,
                1,
                4));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
    }

    @AfterEach
    void removeSubmissions() {
        jdbc.update("delete from submissions");
    }

    private HttpResponse<String> tests(User as) throws Exception {
        return get("/assignments/" + assignment.getId() + "/tests", as == null ? null : tokenFor(as));
    }

    private static List<Map<String, Object>> items(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.tests");
    }

    // --- what a student gets -----------------------------------------------------------------

    @Test
    void aStudentGetsPublicTestsInFullAndHiddenOnesMasked() throws Exception {
        HttpResponse<String> response = tests(student);

        assertThat(response.statusCode()).isEqualTo(200);
        List<Map<String, Object>> items = items(response);
        assertThat(items).hasSize(4);
        assertThat(items.get(0))
                .containsEntry("name", "visible one")
                .containsEntry("input", "1 2")
                .containsEntry("expectedOutput", "3");
        assertThat(items.get(1)).containsEntry("name", "Hidden test 1").containsEntry("visibility", "HIDDEN");
        assertThat(items.get(2)).containsEntry("name", "visible two");
        assertThat(items.get(3)).containsEntry("name", "Hidden test 2");
    }

    @Test
    void theRawJsonOfAHiddenTestHasNoInputNoExpectedOutputAndNoOtherKeys() throws Exception {
        List<Map<String, Object>> items = items(tests(student));

        for (Map<String, Object> item : items) {
            if ("HIDDEN".equals(item.get("visibility"))) {
                assertThat(item.keySet()).isEqualTo(Set.of("id", "name", "visibility", "position"));
                assertThat(item).doesNotContainKeys("input", "expectedOutput", "weight", "criterionId");
            }
        }
    }

    @Test
    void nothingOfAHiddenTestAppearsAnywhereInTheStudentsResponse() throws Exception {
        HttpResponse<String> response = tests(student);

        assertThat(response.body()).doesNotContain(SECRET_NAME, SECRET_INPUT, SECRET_OUTPUT);
    }

    @Test
    void aPublicTestHasTheDocumentedKeysOnly() throws Exception {
        Map<String, Object> first = items(tests(student)).get(0);

        assertThat(first.keySet())
                .isEqualTo(Set.of("id", "name", "input", "expectedOutput", "visibility", "weight", "position"));
        assertThat(first).doesNotContainKeys("criterionId", "assignmentId");
    }

    @Test
    void theOrderIsRunOrderWhateverTheInsertionOrder() throws Exception {
        testCases.save(new TestCase(
                assignment.getId(),
                criterion.getId(),
                "inserted last, runs first",
                "x",
                "y",
                TestVisibility.PUBLIC,
                1,
                0));

        assertThat(items(tests(student)).get(0)).containsEntry("name", "inserted last, runs first");
    }

    @Test
    void theStudentKeepsReadingAfterTheyHaveSubmitted() throws Exception {
        jdbc.update(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no) "
                        + "values (?, ?, 'JAVA', 'x', 1)",
                assignment.getId(),
                student.getId());

        HttpResponse<String> response = tests(student);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(SECRET_INPUT, SECRET_OUTPUT);
    }

    @Test
    void anAssignmentWithOnlyHiddenTestsShowsOnlyMaskedEntries() throws Exception {
        jdbc.update("delete from test_cases where visibility = 'PUBLIC'");

        List<Map<String, Object>> items = items(tests(student));

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(i -> assertThat(i).containsEntry("visibility", "HIDDEN"));
    }

    @Test
    void anAssignmentWithNoTestsGivesAnEmptyList() throws Exception {
        jdbc.update("delete from test_cases");

        HttpResponse<String> response = tests(student);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(items(response)).isEmpty();
    }

    // --- who gets in -------------------------------------------------------------------------

    @Test
    void aDraftIsTheSame404ForAStudentAsAMissingAssignment() throws Exception {
        jdbc.update("update assignments set published = false where id = ?", assignment.getId());

        HttpResponse<String> draft = tests(student);
        HttpResponse<String> missing = get("/assignments/987654321/tests", tokenFor(student));

        assertThat(draft.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(draft.body(), "$.code")).isEqualTo("ASSIGNMENT_NOT_FOUND");
        assertThat(draft.body()).isEqualTo(missing.body());
        assertThat(draft.body()).doesNotContain(SECRET_INPUT, "visible one");
    }

    @Test
    void aStudentWhoIsNotEnrolledGetsTheSame404() throws Exception {
        HttpResponse<String> response = tests(outsider);
        HttpResponse<String> missing = get("/assignments/987654321/tests", tokenFor(outsider));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(missing.body());
    }

    @Test
    void aStudentWhoLeftTheCourseLosesTheList() throws Exception {
        enrollments.deleteAll();

        assertThat(tests(student).statusCode()).isEqualTo(404);
    }

    @Test
    void anAnonymousCallerIsA401() throws Exception {
        assertThat(tests(null).statusCode()).isEqualTo(401);
    }

    // --- the people who run the course still see everything ----------------------------------

    @Test
    void theOwnerAndAnAdminStillGetHiddenTestsInFull() throws Exception {
        for (User as : List.of(owner, admin)) {
            HttpResponse<String> response = tests(as);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(SECRET_NAME, SECRET_INPUT, SECRET_OUTPUT);
            assertThat(items(response).get(1)).containsKeys("input", "expectedOutput", "criterionId", "weight");
        }
    }

    @Test
    void anotherTeacherStillGetsTheNotFoundOfAMissingAssignment() throws Exception {
        HttpResponse<String> response = tests(otherTeacher);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .isEqualTo(get("/assignments/987654321/tests", tokenFor(otherTeacher))
                        .body());
    }

    @Test
    void theStudentsWritesAreStillRefused() throws Exception {
        String url = "/assignments/" + assignment.getId() + "/tests";

        String valid = "{\"criterionId\":" + criterion.getId()
                + ",\"name\":\"t\",\"input\":\"\",\"expectedOutput\":\"\",\"visibility\":\"PUBLIC\",\"weight\":1}";

        assertThat(post(url, valid, tokenFor(student)).statusCode()).isEqualTo(403);
        assertThat(testCases.findAll()).hasSize(4);
    }
}
