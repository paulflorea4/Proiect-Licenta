package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 5.4c: `GET /assignments/{id}/my-grade`, the student's best graded attempt, over real HTTP.
 * Submissions and grades are inserted by SQL with their final status, so no worker grades them.
 */
class RealHttpMyGradeTests extends RealHttpTestBase {

    private static final Instant GRADED_AT = Instant.parse("2030-01-02T03:04:05.123456Z");

    private User owner;
    private User admin;
    private User student;
    private User classmate;
    private User outsider;
    private Course course;
    private Assignment assignment;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
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

    /** A submission already in its final {@code status}; returns its id. */
    private long submission(User by, Assignment to, int attemptNo, String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', ?, ?) returning id",
                Long.class,
                to.getId(),
                by.getId(),
                attemptNo,
                status);
    }

    /** A graded submission with this total out of 100; returns its id. */
    private long graded(User by, Assignment to, int attemptNo, String total) {
        long id = submission(by, to, attemptNo, "GRADED");
        grade(id, total);
        return id;
    }

    private void grade(long submissionId, String total) {
        jdbc.update(
                "insert into grades (submission_id, total_score, max_score, graded_at) values (?, ?, 100.00, ?)",
                submissionId,
                new BigDecimal(total),
                Timestamp.from(GRADED_AT));
    }

    private HttpResponse<String> myGrade(User as, long assignmentId) throws Exception {
        return get("/assignments/" + assignmentId + "/my-grade", as == null ? null : tokenFor(as));
    }

    /** The answer's {@code best.submissionId}, after checking it is a 200. */
    private static long bestSubmission(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.<Number>read(response.body(), "$.best.submissionId").longValue();
    }

    // --- the best attempt --------------------------------------------------------------------

    @Test
    void theHighestTotalCountsAndTheAnswerNamesItsSubmission() throws Exception {
        graded(student, assignment, 1, "40.00");
        long best = graded(student, assignment, 2, "85.50");
        graded(student, assignment, 3, "70.00");

        HttpResponse<String> response = myGrade(student, assignment.getId());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("assignmentId", "best");
        assertThat(JsonPath.<Number>read(response.body(), "$.assignmentId").longValue())
                .isEqualTo(assignment.getId());
        Map<String, Object> bestAttempt = JsonPath.read(response.body(), "$.best");
        assertThat(bestAttempt)
                .containsOnlyKeys("submissionId", "attemptNo", "totalScore", "maxScore", "gradedAt")
                .containsEntry("attemptNo", 2)
                .containsEntry("gradedAt", "2030-01-02T03:04:05.123456Z");
        assertThat(bestSubmission(response)).isEqualTo(best);
        // The scores go out as stored, with their two decimals.
        assertThat(response.body()).contains("\"totalScore\":85.50", "\"maxScore\":100.00");
    }

    @Test
    void ofEqualTotalsTheEarliestAttemptCounts() throws Exception {
        graded(student, assignment, 1, "60.00");
        long first = graded(student, assignment, 2, "90.00");
        graded(student, assignment, 3, "90.00");

        assertThat(bestSubmission(myGrade(student, assignment.getId()))).isEqualTo(first);
    }

    @Test
    void aStudentWithNoGradedAttemptGetsANullBest() throws Exception {
        // Waiting, being graded, a platform failure, and graded without tests (no grade row, 5.3a).
        submission(student, assignment, 1, "QUEUED");
        submission(student, assignment, 2, "RUNNING");
        submission(student, assignment, 3, "ERROR");
        submission(student, assignment, 4, "GRADED");

        HttpResponse<String> response = myGrade(student, assignment.getId());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("assignmentId", "best").containsEntry("best", null);
    }

    @Test
    void aGradeRowCountsOnlyOnAGradedSubmission() throws Exception {
        long counted = graded(student, assignment, 1, "50.00");
        // Higher grades left behind by a run that recovery cut short (5.2c): re-queued, being graded
        // again, and a re-run that then failed.
        grade(submission(student, assignment, 2, "QUEUED"), "95.00");
        grade(submission(student, assignment, 3, "RUNNING"), "99.00");
        grade(submission(student, assignment, 4, "ERROR"), "100.00");

        assertThat(bestSubmission(myGrade(student, assignment.getId()))).isEqualTo(counted);
    }

    @Test
    void onlyTheStudentsOwnAttemptsAtThisAssignmentCount() throws Exception {
        Assignment other = publishedAssignment();
        long own = graded(student, assignment, 1, "30.00");
        long classmates = graded(classmate, assignment, 1, "100.00");
        graded(student, other, 1, "100.00");

        assertThat(bestSubmission(myGrade(student, assignment.getId()))).isEqualTo(own);
        assertThat(bestSubmission(myGrade(classmate, assignment.getId()))).isEqualTo(classmates);
    }

    // --- who may ask -------------------------------------------------------------------------

    @Test
    void anAssignmentTheStudentMayNotSeeIsTheSame404AsAMissingOne() throws Exception {
        graded(student, assignment, 1, "80.00");
        HttpResponse<String> missing = myGrade(student, 987654321L);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(missing.body(), "$.code")).isEqualTo("ASSIGNMENT_NOT_FOUND");

        // Not enrolled in the course.
        assertThat(myGrade(outsider, assignment.getId()).body()).isEqualTo(missing.body());
        // A draft, even one the student has a grade for (unpublished after the attempt).
        jdbc.update("update assignments set published = false where id = ?", assignment.getId());
        HttpResponse<String> draft = myGrade(student, assignment.getId());
        assertThat(draft.statusCode()).isEqualTo(404);
        assertThat(draft.body()).isEqualTo(missing.body());
    }

    @Test
    void teachersAndAdminsAreRefusedByRoleAndNobodyIsAskedToSignIn() throws Exception {
        graded(student, assignment, 1, "80.00");

        for (User staff : new User[] {owner, admin}) {
            HttpResponse<String> response = myGrade(staff, assignment.getId());
            assertThat(response.statusCode()).as(staff.getEmail()).isEqualTo(403);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("ACCESS_DENIED");
        }
        HttpResponse<String> anonymous = myGrade(null, assignment.getId());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(anonymous.body(), "$.code")).isEqualTo("UNAUTHENTICATED");
    }
}
