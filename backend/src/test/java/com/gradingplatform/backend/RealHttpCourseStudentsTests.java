package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.2b: `GET /courses/{id}/students`. */
class RealHttpCourseStudentsTests extends RealHttpTestBase {

    private User teacher;
    private User otherTeacher;
    private User admin;
    private User bob;
    private User eve;
    private User outsider;
    private Course course;
    private Course other;

    @BeforeEach
    void world() {
        teacher = saved("teacher@example.com", Role.TEACHER);
        otherTeacher = saved("other-teacher@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        bob = saved("bob@example.com", Role.STUDENT);
        eve = saved("eve@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, teacher.getId(), "ABCD2345"));
        other = courses.save(new Course("Compilers", null, teacher.getId(), "WXYZ6789"));
        enrollments.save(new Enrollment(course.getId(), bob.getId()));
        enrollments.save(new Enrollment(course.getId(), eve.getId()));
        enrollments.save(new Enrollment(other.getId(), outsider.getId()));
    }

    private String path() {
        return "/courses/" + course.getId() + "/students";
    }

    // --- the list ----------------------------------------------------------------------------

    @Test
    void theOwnerSeesTheEnrolledStudentsOfThatCourseOnly() throws Exception {
        HttpResponse<String> response = get(path(), tokenFor(teacher));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].email"))
                .containsExactly("bob@example.com", "eve@example.com");
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isEqualTo(2);
    }

    @Test
    void aRowHasIdEmailAndNameAndNothingElse() throws Exception {
        HttpResponse<String> response = get(path(), tokenFor(teacher));

        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$.items[0]"))
                .containsOnlyKeys("id", "email", "fullName")
                .containsEntry("id", bob.getId().intValue())
                .containsEntry("fullName", "Name");
        assertThat(response.body()).doesNotContain("hash").doesNotContain("role");
    }

    @Test
    void anAdminSeesAnyCoursesStudents() throws Exception {
        HttpResponse<String> response = get(path(), tokenFor(admin));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].email"))
                .containsExactly("bob@example.com", "eve@example.com");
    }

    @Test
    void aCourseWithoutStudentsHasAnEmptyList() throws Exception {
        Course empty = courses.save(new Course("Empty", null, teacher.getId(), "EMPT2222"));

        HttpResponse<String> response = get("/courses/" + empty.getId() + "/students", tokenFor(teacher));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.items")).isEmpty();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isZero();
    }

    @Test
    void aStudentWhoLeftIsNoLongerListed() throws Exception {
        send("DELETE", "/courses/" + course.getId() + "/enrollment", tokenFor(bob), null);

        assertThat(JsonPath.<List<String>>read(get(path(), tokenFor(teacher)).body(), "$.items[*].email"))
                .containsExactly("eve@example.com");
    }

    @Test
    void aStudentWhoJoinedShowsUp() throws Exception {
        post("/courses/enroll", "{\"code\":\"ABCD2345\"}", tokenFor(outsider));

        assertThat(JsonPath.<List<String>>read(get(path(), tokenFor(teacher)).body(), "$.items[*].email"))
                .containsExactly("bob@example.com", "eve@example.com", "outsider@example.com");
    }

    // --- pagination --------------------------------------------------------------------------

    @Test
    void itIsPaginatedInStudentIdOrderWithoutOverlap() throws Exception {
        HttpResponse<String> first = get(path() + "?page=0&size=1", tokenFor(teacher));
        HttpResponse<String> second = get(path() + "?page=1&size=1", tokenFor(teacher));
        HttpResponse<String> past = get(path() + "?page=2&size=1", tokenFor(teacher));

        assertThat(JsonPath.<List<String>>read(first.body(), "$.items[*].email"))
                .containsExactly("bob@example.com");
        assertThat(JsonPath.<List<String>>read(second.body(), "$.items[*].email"))
                .containsExactly("eve@example.com");
        assertThat(JsonPath.<Integer>read(first.body(), "$.totalPages")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(first.body(), "$.size")).isEqualTo(1);
        assertThat(past.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(past.body(), "$.items")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=-3", "?page=abc"})
    void badPagingParametersAreA400(String query) throws Exception {
        assertThat(get(path() + query, tokenFor(teacher)).statusCode()).isEqualTo(400);
    }

    // --- who may see it ----------------------------------------------------------------------

    @Test
    void anotherTeacherGetsTheSame404AsForAMissingCourse() throws Exception {
        HttpResponse<String> notTheirs = get(path(), tokenFor(otherTeacher));
        HttpResponse<String> missing = get("/courses/987654321/students", tokenFor(otherTeacher));

        assertThat(notTheirs.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(notTheirs.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
        assertThat(notTheirs.body()).isEqualTo(missing.body());
        assertThat(notTheirs.body()).doesNotContain("bob@example.com");
    }

    @Test
    void studentsAreRefusedEvenWhenEnrolledInTheCourse() throws Exception {
        HttpResponse<String> response = get(path(), tokenFor(bob));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("ACCESS_DENIED");
        assertThat(response.body()).doesNotContain("eve@example.com");
        assertThat(get(path(), tokenFor(outsider)).statusCode()).isEqualTo(403);
    }

    @Test
    void nobodyWithoutAValidTokenCanSeeIt() throws Exception {
        assertThat(get(path(), null).statusCode()).isEqualTo(401);
        assertThat(get(path(), "not.a.jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void aMalformedCourseIdIsA400() throws Exception {
        assertThat(get("/courses/abc/students", tokenFor(teacher)).statusCode()).isEqualTo(400);
    }
}
