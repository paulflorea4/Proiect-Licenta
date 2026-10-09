package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.1c: `GET /courses/{id}` and who may see which course. */
class RealHttpCourseGetTests extends RealHttpTestBase {

    private User owner;
    private User otherTeacher;
    private User enrolled;
    private User outsider;
    private User admin;
    private Course course;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        enrolled = saved("enrolled@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        admin = saved("root@example.com", Role.ADMIN);
        course = courses.save(new Course("Algorithms", "Sorting and graphs", owner.getId(), "CODE0001"));
        enrollments.save(new Enrollment(course.getId(), enrolled.getId()));
    }

    private HttpResponse<String> getCourse(User caller) throws Exception {
        return get("/courses/" + course.getId(), tokenFor(caller));
    }

    // --- who sees it -------------------------------------------------------------------------

    @Test
    void theOwningTeacherSeesTheCourseWithItsCode() throws Exception {
        HttpResponse<String> response = getCourse(owner);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.body(), "$.id"))
                .isEqualTo(course.getId().intValue());
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Algorithms");
        assertThat(JsonPath.<String>read(response.body(), "$.description")).isEqualTo("Sorting and graphs");
        assertThat(JsonPath.<Integer>read(response.body(), "$.teacherId"))
                .isEqualTo(owner.getId().intValue());
        assertThat(JsonPath.<String>read(response.body(), "$.enrollCode")).isEqualTo("CODE0001");
    }

    @Test
    void anAdminSeesAnyCourseWithItsCode() throws Exception {
        HttpResponse<String> response = getCourse(admin);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.enrollCode")).isEqualTo("CODE0001");
    }

    @Test
    void anEnrolledStudentSeesTheCourseWithoutTheCode() throws Exception {
        HttpResponse<String> response = getCourse(enrolled);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Algorithms");
        assertThat(response.body()).doesNotContain("enrollCode").doesNotContain("CODE0001");
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$").keySet())
                .containsExactlyInAnyOrder("id", "title", "description", "teacherId", "createdAt");
    }

    @Test
    void anotherTeacherDoesNotSeeTheCourse() throws Exception {
        assertThat(getCourse(otherTeacher).statusCode()).isEqualTo(404);
    }

    @Test
    void aStudentWhoIsNotEnrolledDoesNotSeeTheCourse() throws Exception {
        assertThat(getCourse(outsider).statusCode()).isEqualTo(404);
    }

    @Test
    void enrollmentInAnotherCourseGivesNoAccessToThisOne() throws Exception {
        Course elsewhere = courses.save(new Course("Compilers", null, otherTeacher.getId(), "CODE0002"));
        enrollments.save(new Enrollment(elsewhere.getId(), outsider.getId()));

        assertThat(getCourse(outsider).statusCode()).isEqualTo(404);
        assertThat(get("/courses/" + elsewhere.getId(), tokenFor(outsider)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void anEnrolledTeacherDoesNotSeeSomeoneElsesCourse() throws Exception {
        // The role decides, as in the list: a teacher sees what they own, enrolled or not.
        enrollments.save(new Enrollment(course.getId(), otherTeacher.getId()));

        assertThat(getCourse(otherTeacher).statusCode()).isEqualTo(404);
    }

    @Test
    void accessFollowsEnrollmentAtOnce() throws Exception {
        assertThat(getCourse(outsider).statusCode()).isEqualTo(404);

        enrollments.save(new Enrollment(course.getId(), outsider.getId()));

        assertThat(getCourse(outsider).statusCode()).isEqualTo(200);
    }

    // --- a course you may not see is a course that does not exist -----------------------------

    @Test
    void aCourseYouMayNotSeeIsIndistinguishableFromOneThatDoesNotExist() throws Exception {
        HttpResponse<String> hidden = getCourse(outsider);
        HttpResponse<String> missing = get("/courses/987654321", tokenFor(outsider));

        assertThat(hidden.statusCode()).isEqualTo(404).isEqualTo(missing.statusCode());
        assertThat(JsonPath.<String>read(hidden.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
        assertThat(hidden.body()).isEqualTo(missing.body());
        assertThat(hidden.headers().map().keySet())
                .isEqualTo(missing.headers().map().keySet());
        assertThat(hidden.body()).doesNotContain("Algorithms").doesNotContain("CODE0001");
    }

    @Test
    void aMissingCourseIs404ForEveryRole() throws Exception {
        for (User caller : new User[] {owner, enrolled, admin}) {
            HttpResponse<String> response = get("/courses/987654321", tokenFor(caller));

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void idsThatCannotExistAre404(String id) throws Exception {
        assertThat(get("/courses/" + id, tokenFor(admin)).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    void anIdThatIsNotANumberIsAMalformedRequest(String id) throws Exception {
        HttpResponse<String> response = get("/courses/" + id, tokenFor(admin));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    // --- who may call ------------------------------------------------------------------------

    @Test
    void nobodyWithoutAValidTokenSeesAnything() throws Exception {
        assertThat(get("/courses/" + course.getId(), null).statusCode()).isEqualTo(401);
        assertThat(get("/courses/" + course.getId(), "not.a.jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void putAndDeleteOnACourseDoNotExistYet() throws Exception {
        // They arrive in 3.1d; until then they answer 405 and change nothing.
        String token = tokenFor(owner);

        assertThat(send("PUT", "/courses/" + course.getId(), token, "{\"title\":\"X\"}")
                        .statusCode())
                .isEqualTo(405);
        assertThat(send("DELETE", "/courses/" + course.getId(), token, null).statusCode())
                .isEqualTo(405);
        assertThat(courses.findById(course.getId()).orElseThrow().getTitle()).isEqualTo("Algorithms");
    }
}
