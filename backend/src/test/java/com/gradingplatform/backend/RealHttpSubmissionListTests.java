package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 5.5c: `GET /assignments/{id}/submissions` (the people who run the course) and `GET
 * /assignments/{id}/my-submissions` (a student's own), paginated, over real HTTP. Submissions and
 * grades are inserted by SQL with their final status, so no worker grades them.
 */
class RealHttpSubmissionListTests extends RealHttpTestBase {

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private User classmate;
    private User outsider;
    private Course course;
    private Assignment assignment;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
        assignment = publishedAssignment();
    }

    private Assignment publishedAssignment() {
        Assignment saved = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", saved.getId());
        return saved;
    }

    private long submission(User by, Assignment to, int attemptNo, String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'SECRET-CODE', ?, ?) returning id",
                Long.class,
                to.getId(),
                by.getId(),
                attemptNo,
                status);
    }

    private void grade(long submissionId, String total, boolean provisional) {
        jdbc.update(
                "insert into grades (submission_id, total_score, max_score, provisional) values (?, ?::numeric, 100.00, ?)",
                submissionId,
                total,
                provisional);
    }

    private HttpResponse<String> list(User as, long assignmentId, String query) throws Exception {
        return get("/assignments/" + assignmentId + "/submissions" + query, as == null ? null : tokenFor(as));
    }

    private HttpResponse<String> mine(User as, long assignmentId, String query) throws Exception {
        return get("/assignments/" + assignmentId + "/my-submissions" + query, as == null ? null : tokenFor(as));
    }

    private static List<Long> ids(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.<List<Number>>read(response.body(), "$.items[*].id").stream()
                .map(Number::longValue)
                .toList();
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    // --- the teacher's list ------------------------------------------------------------------

    @Test
    void theOwnerSeesEverySubmissionInIdOrderWithItsStudentAndGrade() throws Exception {
        long first = submission(student, assignment, 1, "GRADED");
        grade(first, "40.00", false);
        long second = submission(classmate, assignment, 1, "GRADED");
        grade(second, "85.50", true);
        long third = submission(student, assignment, 2, "QUEUED");
        submission(student, publishedAssignment(), 1, "GRADED"); // another assignment's

        HttpResponse<String> response = list(owner, assignment.getId(), "");

        assertThat(ids(response)).containsExactly(first, second, third);
        String body = response.body();
        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(3);
        Map<String, Object> row = JsonPath.read(body, "$.items[1]");
        assertThat(row)
                .containsOnlyKeys("id", "student", "attemptNo", "status", "submittedAt", "finishedAt", "grade")
                .containsEntry("attemptNo", 1)
                .containsEntry("status", "GRADED");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.items[1].student"))
                .containsOnlyKeys("id", "email", "fullName")
                .containsEntry("email", "classmate@example.com");
        assertThat(body).contains("\"grade\":{\"totalScore\":85.50,\"maxScore\":100.00,\"provisional\":true}");
        assertThat(JsonPath.<Object>read(body, "$.items[2].grade")).isNull();
        assertThat(body).as("no code in a list").doesNotContain("SECRET-CODE");
    }

    @Test
    void aGradeIsShownOnlyForAGradedSubmission() throws Exception {
        // A grade left on a submission recovery put back in the queue, or whose re-run failed (5.2c).
        long requeued = submission(student, assignment, 1, "QUEUED");
        grade(requeued, "95.00", false);
        long failed = submission(student, assignment, 2, "ERROR");
        grade(failed, "99.00", false);
        submission(student, assignment, 3, "GRADED"); // graded without tests: no grade row

        String body = list(owner, assignment.getId(), "").body();

        assertThat(JsonPath.<List<Object>>read(body, "$.items[*].grade")).containsExactly(null, null, null);
    }

    @Test
    void theListIsPaginatedAndAnAdminSeesItToo() throws Exception {
        long a = submission(student, assignment, 1, "QUEUED");
        long b = submission(student, assignment, 2, "QUEUED");
        long c = submission(classmate, assignment, 1, "QUEUED");

        HttpResponse<String> firstPage = list(admin, assignment.getId(), "?page=0&size=2");
        HttpResponse<String> secondPage = list(admin, assignment.getId(), "?page=1&size=2");

        assertThat(ids(firstPage)).containsExactly(a, b);
        assertThat(ids(secondPage)).containsExactly(c);
        assertThat(JsonPath.<Integer>read(firstPage.body(), "$.totalPages")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(firstPage.body(), "$.size")).isEqualTo(2);
        assertThat(list(owner, assignment.getId(), "?size=0").statusCode()).isEqualTo(400);
    }

    @Test
    void aDraftsSubmissionsAreListedForTheOwner() throws Exception {
        long id = submission(student, assignment, 1, "GRADED");
        jdbc.update("update assignments set published = false where id = ?", assignment.getId());

        assertThat(ids(list(owner, assignment.getId(), ""))).containsExactly(id);
    }

    @Test
    void anotherTeacherGetsTheSame404AsAMissingAssignmentAndStudentsAre403() throws Exception {
        submission(student, assignment, 1, "GRADED");
        HttpResponse<String> missing = list(owner, 987654321L, "");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("ASSIGNMENT_NOT_FOUND");

        HttpResponse<String> other = list(otherTeacher, assignment.getId(), "");
        assertThat(other.body()).isEqualTo(missing.body());

        HttpResponse<String> asStudent = list(student, assignment.getId(), "");
        assertThat(asStudent.statusCode()).isEqualTo(403);
        assertThat(code(asStudent)).isEqualTo("ACCESS_DENIED");
        assertThat(list(null, assignment.getId(), "").statusCode()).isEqualTo(401);
    }

    // --- the student's own list --------------------------------------------------------------

    @Test
    void aStudentSeesOnlyTheirOwnSubmissionsInIdOrder() throws Exception {
        long first = submission(student, assignment, 1, "GRADED");
        grade(first, "60.00", true);
        submission(classmate, assignment, 1, "GRADED");
        long second = submission(student, assignment, 2, "RUNNING");
        submission(student, publishedAssignment(), 1, "GRADED"); // another assignment's

        HttpResponse<String> response = mine(student, assignment.getId(), "");

        assertThat(ids(response)).containsExactly(first, second);
        String body = response.body();
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.items[0]"))
                .containsOnlyKeys("id", "attemptNo", "status", "submittedAt", "finishedAt", "grade");
        assertThat(body).contains("\"grade\":{\"totalScore\":60.00,\"maxScore\":100.00,\"provisional\":true}");
        assertThat(JsonPath.<Object>read(body, "$.items[1].grade")).isNull();
        assertThat(body).doesNotContain("SECRET-CODE", "classmate@example.com");
    }

    @Test
    void theStudentsListIsPaginated() throws Exception {
        long a = submission(student, assignment, 1, "QUEUED");
        long b = submission(student, assignment, 2, "QUEUED");

        assertThat(ids(mine(student, assignment.getId(), "?page=1&size=1"))).containsExactly(b);
        assertThat(ids(mine(student, assignment.getId(), "?size=1"))).containsExactly(a);
        assertThat(mine(student, assignment.getId(), "?page=-1").statusCode()).isEqualTo(400);
    }

    @Test
    void anAssignmentTheStudentMayNotSeeIsTheSame404AsAMissingOne() throws Exception {
        submission(student, assignment, 1, "GRADED");
        HttpResponse<String> missing = mine(student, 987654321L, "");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("ASSIGNMENT_NOT_FOUND");

        assertThat(mine(outsider, assignment.getId(), "").body()).isEqualTo(missing.body());
        jdbc.update("update assignments set published = false where id = ?", assignment.getId());
        assertThat(mine(student, assignment.getId(), "").body()).isEqualTo(missing.body());
    }

    @Test
    void teachersAndAdminsAreRefusedTheStudentsListByRole() throws Exception {
        for (User staff : new User[] {owner, admin}) {
            HttpResponse<String> response = mine(staff, assignment.getId(), "");
            assertThat(response.statusCode()).as(staff.getEmail()).isEqualTo(403);
        }
        assertThat(mine(null, assignment.getId(), "").statusCode()).isEqualTo(401);
    }
}
