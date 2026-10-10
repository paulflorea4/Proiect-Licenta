package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 3.3c: `PUT /assignments/{id}`, publish / unpublish and `DELETE /assignments/{id}`. */
class RealHttpAssignmentChangeTests extends RealHttpTestBase {

    @Autowired
    DataSource dataSource;

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private Course course;
    private Assignment assignment;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "Sort the numbers",
                "JAVA",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                3,
                2000,
                256,
                "class Main {}"));
    }

    /** Rows that reference an assignment go before the base class deletes the assignments. */
    @AfterEach
    void removeWhatReferencesAssignments() {
        jdbc.update("delete from submissions");
        jdbc.update("delete from test_cases");
        jdbc.update("delete from rubric_criteria");
    }

    // --- helpers -----------------------------------------------------------------------------

    private static String future(long days) {
        return Instant.now()
                .plus(days, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    /** A valid body that differs from the stored assignment in every editable field. */
    private static String body(String... overrides) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("title", "\"Sorting, take two\"");
        fields.put("description", "\"Sort them faster\"");
        fields.put("language", "\"JAVA\"");
        fields.put("deadline", "\"" + future(14) + "\"");
        fields.put("maxAttempts", "5");
        fields.put("timeLimitMs", "3000");
        fields.put("memoryLimitMb", "512");
        fields.put("starterCode", "\"class Solution {}\"");
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

    private String path() {
        return "/assignments/" + assignment.getId();
    }

    private HttpResponse<String> put(User as, String json) throws Exception {
        return send("PUT", path(), as == null ? null : tokenFor(as), json);
    }

    private HttpResponse<String> publish(User as) throws Exception {
        return send("POST", path() + "/publish", as == null ? null : tokenFor(as), null);
    }

    private HttpResponse<String> unpublish(User as) throws Exception {
        return send("POST", path() + "/unpublish", as == null ? null : tokenFor(as), null);
    }

    private HttpResponse<String> delete(User as) throws Exception {
        return send("DELETE", path(), as == null ? null : tokenFor(as), null);
    }

    private Assignment stored() {
        return assignments.findById(assignment.getId()).orElseThrow();
    }

    private void submit(long studentId) {
        jdbc.update(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no) "
                        + "values (?, ?, 'JAVA', 'class Main {}', 1)",
                assignment.getId(),
                studentId);
    }

    /** A rubric that can be published: one `TESTS` criterion worth 100 with one test case. */
    private long addCriterionWithTest() {
        long criterionId = jdbc.queryForObject(
                "insert into rubric_criteria (assignment_id, name, type, weight) values (?, 'Tests', 'TESTS', 100) "
                        + "returning id",
                Long.class,
                assignment.getId());
        jdbc.update(
                "insert into test_cases (assignment_id, criterion_id, name, input, expected_output, visibility, position) "
                        + "values (?, ?, 't1', '1', '1', 'PUBLIC', 1)",
                assignment.getId(),
                criterionId);
        return criterionId;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    // --- PUT ---------------------------------------------------------------------------------

    @Test
    void theOwnerReplacesTheEditableFields() throws Exception {
        Instant createdAt = stored().getCreatedAt();
        Instant updatedBefore = stored().getUpdatedAt();

        HttpResponse<String> response = put(owner, body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Sorting, take two");
        assertThat(JsonPath.<Integer>read(response.body(), "$.maxAttempts")).isEqualTo(5);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.published")).isFalse();
        Assignment now = stored();
        assertThat(now.getTitle()).isEqualTo("Sorting, take two");
        assertThat(now.getDescription()).isEqualTo("Sort them faster");
        assertThat(now.getTimeLimitMs()).isEqualTo(3000);
        assertThat(now.getMemoryLimitMb()).isEqualTo(512);
        assertThat(now.getMaxAttempts()).isEqualTo(5);
        assertThat(now.getStarterCode()).isEqualTo("class Solution {}");
        assertThat(now.getCourseId()).isEqualTo(course.getId());
        assertThat(now.getCreatedAt()).isEqualTo(createdAt);
        assertThat(now.getUpdatedAt()).isAfter(updatedBefore);
        assertThat(JsonPath.<String>read(response.body(), "$.updatedAt"))
                .isEqualTo(now.getUpdatedAt().toString());
    }

    @Test
    void optionalFieldsCanBeCleared() throws Exception {
        HttpResponse<String> response = put(owner, body("maxAttempts", null, "starterCode", null));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(stored().getMaxAttempts()).isNull();
        assertThat(stored().getStarterCode()).isNull();
    }

    @Test
    void anAdminMayEditToo() throws Exception {
        assertThat(put(admin, body()).statusCode()).isEqualTo(200);
    }

    @Test
    void publishedAndIdInTheBodyAreIgnored() throws Exception {
        HttpResponse<String> response = put(owner, body("published", "true", "id", "999", "courseId", "999"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(stored().isPublished()).isFalse();
        assertThat(stored().getCourseId()).isEqualTo(course.getId());
    }

    @Test
    void theLanguageCanChangeWhileThereAreNoSubmissions() throws Exception {
        HttpResponse<String> response = put(owner, body("language", "\" python \""));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(stored().getLanguage()).isEqualTo("PYTHON");
    }

    @Test
    void anUnsupportedNewLanguageIsA400() throws Exception {
        HttpResponse<String> response = put(owner, body("language", "\"COBOL\""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("UNSUPPORTED_LANGUAGE");
        assertThat(stored().getLanguage()).isEqualTo("JAVA");
    }

    @Test
    void aLanguageThatIsNotBeingChangedIsNotCheckedAgain() throws Exception {
        jdbc.update("update assignments set language = 'RUST' where id = ?", assignment.getId());

        assertThat(put(owner, body("language", "\"rust\"")).statusCode()).isEqualTo(200);
    }

    @Test
    void aChangedDeadlineMustBeInTheFuture() throws Exception {
        HttpResponse<String> response = put(owner, body("deadline", "\"2001-01-01T00:00:00Z\""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("DEADLINE_NOT_IN_FUTURE");
        assertThat(stored().getDeadline()).isAfter(Instant.now());
    }

    @Test
    void anUnchangedPastDeadlineDoesNotBlockOtherEdits() throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update(
                "update assignments set deadline = ? where id = ?", java.sql.Timestamp.from(past), assignment.getId());

        HttpResponse<String> response = put(owner, body("deadline", "\"" + past + "\""));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(stored().getDeadline()).isEqualTo(past);
        assertThat(stored().getTitle()).isEqualTo("Sorting, take two");
    }

    @Test
    void everythingButTheLanguageStaysEditableAfterASubmission() throws Exception {
        submit(student.getId());

        HttpResponse<String> response = put(owner, body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(stored().getTitle()).isEqualTo("Sorting, take two");
        assertThat(stored().getLanguage()).isEqualTo("JAVA");
    }

    @Test
    void theLanguageIsLockedAfterASubmission() throws Exception {
        submit(student.getId());

        HttpResponse<String> response = put(owner, body("language", "\"PYTHON\""));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("ASSIGNMENT_LANGUAGE_LOCKED");
        assertThat(stored().getLanguage()).isEqualTo("JAVA");
        assertThat(stored().getTitle()).isEqualTo("Sorting");
    }

    @Test
    void sendingTheSameLanguageInAnotherSpellingIsNotAChangeEvenWithSubmissions() throws Exception {
        submit(student.getId());

        assertThat(put(owner, body("language", "\"java\"")).statusCode()).isEqualTo(200);
    }

    @Test
    void aSubmissionBeingMadeRightNowDecidesTheLanguageLock() throws Exception {
        // An uncommitted insert holds a lock on the assignment row; the edit has to wait for it
        // and then see the submission, not slip a language change in beside it.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement(
                    "insert into submissions (assignment_id, student_id, language, source_code, attempt_no) "
                            + "values (?, ?, 'JAVA', 'x', 1)")) {
                insert.setLong(1, assignment.getId());
                insert.setLong(2, student.getId());
                insert.executeUpdate();

                CompletableFuture<HttpResponse<String>> edit = CompletableFuture.supplyAsync(() -> {
                    try {
                        return put(owner, body("language", "\"PYTHON\""));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(500);
                assertThat(edit).isNotDone();

                connection.commit();
                HttpResponse<String> response = edit.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(409);
                assertThat(code(response)).isEqualTo("ASSIGNMENT_LANGUAGE_LOCKED");
            }
        }
        assertThat(stored().getLanguage()).isEqualTo("JAVA");
    }

    @Test
    void anInvalidBodyIsA400AndChangesNothing() throws Exception {
        HttpResponse<String> response = put(owner, body("title", "\"   \""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(stored().getTitle()).isEqualTo("Sorting");
    }

    @Test
    void anotherTeacherGetsTheSame404AsForAMissingAssignment() throws Exception {
        HttpResponse<String> other = put(otherTeacher, body());
        HttpResponse<String> missing = send("PUT", "/assignments/987654321", tokenFor(otherTeacher), body());

        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(code(other)).isEqualTo("ASSIGNMENT_NOT_FOUND");
        assertThat(other.body()).isEqualTo(missing.body());
        assertThat(stored().getTitle()).isEqualTo("Sorting");
    }

    @Test
    void studentsAreRefusedAndAnonymousCallersAreUnauthenticated() throws Exception {
        assertThat(put(student, body()).statusCode()).isEqualTo(403);
        assertThat(put(null, body()).statusCode()).isEqualTo(401);
        assertThat(stored().getTitle()).isEqualTo("Sorting");
    }

    // --- publish / unpublish -----------------------------------------------------------------

    @Test
    void publishingShowsTheAssignmentToStudentsAndUnpublishingHidesIt() throws Exception {
        addCriterionWithTest();
        String studentToken = tokenFor(student);
        assertThat(get(path(), studentToken).statusCode()).isEqualTo(404);

        HttpResponse<String> published = publish(owner);

        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(published.body(), "$.published")).isTrue();
        assertThat(stored().isPublished()).isTrue();
        assertThat(get(path(), studentToken).statusCode()).isEqualTo(200);

        HttpResponse<String> hidden = unpublish(owner);

        assertThat(hidden.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(hidden.body(), "$.published")).isFalse();
        assertThat(get(path(), studentToken).statusCode()).isEqualTo(404);
    }

    @Test
    void publishingTwiceAndUnpublishingADraftAreNoOps() throws Exception {
        addCriterionWithTest();
        assertThat(unpublish(owner).statusCode()).isEqualTo(200);
        Instant untouched = stored().getUpdatedAt();

        assertThat(publish(owner).statusCode()).isEqualTo(200);
        Instant afterFirst = stored().getUpdatedAt();
        assertThat(publish(owner).statusCode()).isEqualTo(200);

        assertThat(afterFirst).isAfter(untouched);
        assertThat(stored().getUpdatedAt()).isEqualTo(afterFirst);
        assertThat(stored().isPublished()).isTrue();
    }

    @Test
    void anAdminCanPublish() throws Exception {
        addCriterionWithTest();
        assertThat(publish(admin).statusCode()).isEqualTo(200);
        assertThat(stored().isPublished()).isTrue();
    }

    @Test
    void anAssignmentWithSubmissionsCanStillBeHidden() throws Exception {
        addCriterionWithTest();
        publish(owner);
        submit(student.getId());

        assertThat(unpublish(owner).statusCode()).isEqualTo(200);
        assertThat(stored().isPublished()).isFalse();
    }

    @Test
    void publishingAndUnpublishingFollowTheSameAccessRules() throws Exception {
        assertThat(publish(otherTeacher).statusCode()).isEqualTo(404);
        assertThat(unpublish(otherTeacher).statusCode()).isEqualTo(404);
        assertThat(publish(student).statusCode()).isEqualTo(403);
        assertThat(unpublish(student).statusCode()).isEqualTo(403);
        assertThat(publish(null).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/assignments/987654321/publish", tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(stored().isPublished()).isFalse();
    }

    // --- DELETE ------------------------------------------------------------------------------

    @Test
    void theOwnerDeletesAnAssignmentWithItsRubricAndTests() throws Exception {
        addCriterionWithTest();
        Assignment sibling = assignments.save(new Assignment(
                course.getId(), "Other", "d", "JAVA", Instant.now().plusSeconds(3600), null, 1000, 64, null));

        HttpResponse<String> response = delete(owner);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(assignments.findAll()).extracting(Assignment::getId).containsExactly(sibling.getId());
        assertThat(count("test_cases")).isZero();
        assertThat(count("rubric_criteria")).isZero();
        assertThat(get(path(), tokenFor(owner)).statusCode()).isEqualTo(404);
    }

    @Test
    void deletingTwiceIsA404TheSecondTime() throws Exception {
        assertThat(delete(owner).statusCode()).isEqualTo(204);

        HttpResponse<String> again = delete(owner);

        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(code(again)).isEqualTo("ASSIGNMENT_NOT_FOUND");
    }

    @Test
    void anAdminMayDelete() throws Exception {
        assertThat(delete(admin).statusCode()).isEqualTo(204);
        assertThat(assignments.findAll()).isEmpty();
    }

    @Test
    void anAssignmentWithSubmissionsCannotBeDeletedAndNothingIsRemoved() throws Exception {
        addCriterionWithTest();
        submit(student.getId());

        HttpResponse<String> response = delete(owner);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("ASSIGNMENT_HAS_SUBMISSIONS");
        assertThat(assignments.findAll()).hasSize(1);
        assertThat(count("test_cases")).isEqualTo(1);
        assertThat(count("rubric_criteria")).isEqualTo(1);
        assertThat(count("submissions")).isEqualTo(1);
    }

    @Test
    void deleteFollowsTheSameAccessRules() throws Exception {
        assertThat(delete(otherTeacher).statusCode()).isEqualTo(404);
        assertThat(delete(student).statusCode()).isEqualTo(403);
        assertThat(delete(null).statusCode()).isEqualTo(401);
        assertThat(assignments.findAll()).hasSize(1);
    }

    @Test
    void theCourseCanBeDeletedOnceItsLastAssignmentIsGone() throws Exception {
        assertThat(send("DELETE", "/courses/" + course.getId(), tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(409);

        delete(owner);

        assertThat(send("DELETE", "/courses/" + course.getId(), tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(204);
    }
}
