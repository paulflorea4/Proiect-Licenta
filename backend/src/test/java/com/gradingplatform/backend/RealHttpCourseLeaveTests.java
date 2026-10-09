package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.EnrollmentId;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 3.2b: `DELETE /courses/{id}/enrollment`. */
class RealHttpCourseLeaveTests extends RealHttpTestBase {

    private User teacher;
    private User bob;
    private User eve;
    private Course course;
    private Course other;

    @BeforeEach
    void world() {
        teacher = saved("teacher@example.com", Role.TEACHER);
        bob = saved("bob@example.com", Role.STUDENT);
        eve = saved("eve@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, teacher.getId(), "ABCD2345"));
        other = courses.save(new Course("Compilers", null, teacher.getId(), "WXYZ6789"));
        enrollments.save(new Enrollment(course.getId(), bob.getId()));
        enrollments.save(new Enrollment(course.getId(), eve.getId()));
        enrollments.save(new Enrollment(other.getId(), bob.getId()));
    }

    /** Runs before the base class empties `assignments`, which the submissions reference. */
    @AfterEach
    void removeSubmissions() {
        jdbc.update("DELETE FROM submissions");
    }

    private String path(Course c) {
        return "/courses/" + c.getId() + "/enrollment";
    }

    private HttpResponse<String> leave(User student, Course c) throws Exception {
        return send("DELETE", path(c), tokenFor(student), null);
    }

    private long addAssignment(Course to) {
        return jdbc.queryForObject(
                "INSERT INTO assignments (course_id, title, description, language, deadline, time_limit_ms,"
                        + " memory_limit_mb) VALUES (?, 'Sorting', 'Sort it', 'JAVA', NOW() + INTERVAL '1 day',"
                        + " 5000, 256) RETURNING id",
                Long.class,
                to.getId());
    }

    // --- leaving -----------------------------------------------------------------------------

    @Test
    void aStudentLeavesAndLosesAccessToThatCourseOnly() throws Exception {
        HttpResponse<String> response = leave(bob, course);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(enrollments.findAll())
                .extracting(Enrollment::getId)
                .containsExactlyInAnyOrder(
                        new EnrollmentId(course.getId(), eve.getId()), new EnrollmentId(other.getId(), bob.getId()));
        assertThat(get("/courses/" + course.getId(), tokenFor(bob)).statusCode())
                .isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(get("/courses", tokenFor(bob)).body(), "$.items[*].title"))
                .containsExactly("Compilers");
    }

    @Test
    void theOtherStudentsOfTheCourseStayIn() throws Exception {
        leave(bob, course);

        assertThat(get("/courses/" + course.getId(), tokenFor(eve)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void aStudentCanJoinAgainWithTheCodeAfterLeaving() throws Exception {
        leave(bob, course);

        assertThat(post("/courses/enroll", "{\"code\":\"ABCD2345\"}", tokenFor(bob))
                        .statusCode())
                .isEqualTo(200);

        assertThat(get("/courses/" + course.getId(), tokenFor(bob)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void leavingKeepsWhatTheStudentSubmitted() throws Exception {
        long assignment = addAssignment(course);
        jdbc.update(
                "INSERT INTO submissions (assignment_id, student_id, language, source_code, attempt_no)"
                        + " VALUES (?, ?, 'JAVA', 'class A {}', 1)",
                assignment,
                bob.getId());

        assertThat(leave(bob, course).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM submissions WHERE student_id = ? AND assignment_id = ?",
                        Integer.class,
                        bob.getId(),
                        assignment))
                .isEqualTo(1);
    }

    // --- nothing to leave --------------------------------------------------------------------

    @Test
    void leavingTwiceIsA404TheSecondTime() throws Exception {
        assertThat(leave(bob, course).statusCode()).isEqualTo(204);

        HttpResponse<String> second = leave(bob, course);

        assertThat(second.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(second.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
    }

    @Test
    void aStudentWhoWasNeverInTheCourseGetsTheSameAnswerAsForAMissingCourse() throws Exception {
        User outsider = saved("outsider@example.com", Role.STUDENT);

        HttpResponse<String> notInIt = leave(outsider, course);
        HttpResponse<String> missing = send("DELETE", "/courses/987654321/enrollment", tokenFor(outsider), null);

        assertThat(notInIt.statusCode()).isEqualTo(404);
        assertThat(notInIt.body()).isEqualTo(missing.body());
        assertThat(enrollments.count()).isEqualTo(3);
    }

    @Test
    void aStudentCannotRemoveAnotherStudent() throws Exception {
        User outsider = saved("outsider@example.com", Role.STUDENT);

        leave(outsider, course);

        assertThat(enrollments.existsById(new EnrollmentId(course.getId(), bob.getId())))
                .isTrue();
        assertThat(enrollments.existsById(new EnrollmentId(course.getId(), eve.getId())))
                .isTrue();
    }

    @Test
    void aMalformedCourseIdIsA400() throws Exception {
        assertThat(send("DELETE", "/courses/abc/enrollment", tokenFor(bob), null)
                        .statusCode())
                .isEqualTo(400);
    }

    // --- who may call ------------------------------------------------------------------------

    @Test
    void teachersAndAdminsCannotUseIt() throws Exception {
        User admin = saved("root@example.com", Role.ADMIN);

        HttpResponse<String> asTeacher = leave(teacher, course);
        HttpResponse<String> asAdmin = leave(admin, course);

        assertThat(asTeacher.statusCode()).isEqualTo(403);
        assertThat(asAdmin.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(asTeacher.body(), "$.code")).isEqualTo("ACCESS_DENIED");
        assertThat(enrollments.count()).isEqualTo(3);
    }

    @Test
    void nobodyWithoutAValidTokenCanUseIt() throws Exception {
        assertThat(send("DELETE", path(course), null, null).statusCode()).isEqualTo(401);
        assertThat(send("DELETE", path(course), "not.a.jwt", null).statusCode()).isEqualTo(401);
        assertThat(enrollments.count()).isEqualTo(3);
    }
}
