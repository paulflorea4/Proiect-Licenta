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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.1d: `PUT /courses/{id}` and `DELETE /courses/{id}`. */
class RealHttpCourseChangeTests extends RealHttpTestBase {

    private static final String NEW_VALUES = "{\"title\":\"Advanced Algorithms\",\"description\":\"Graphs only\"}";

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

    private String path() {
        return "/courses/" + course.getId();
    }

    private void addAssignment(Course to) {
        jdbc.update(
                "INSERT INTO assignments (course_id, title, description, language, deadline, time_limit_ms,"
                        + " memory_limit_mb) VALUES (?, 'Sorting', 'Sort it', 'JAVA', NOW() + INTERVAL '1 day',"
                        + " 5000, 256)",
                to.getId());
    }

    private void assertCourseUntouched() {
        Course stored = courses.findById(course.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Algorithms");
        assertThat(stored.getDescription()).isEqualTo("Sorting and graphs");
        assertThat(stored.getTeacherId()).isEqualTo(owner.getId());
        assertThat(stored.getEnrollCode()).isEqualTo("CODE0001");
    }

    // === PUT ===================================================================================

    @Test
    void theOwnerChangesTitleAndDescriptionAndNothingElse() throws Exception {
        HttpResponse<String> response = send("PUT", path(), tokenFor(owner), NEW_VALUES);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Advanced Algorithms");
        assertThat(JsonPath.<String>read(response.body(), "$.description")).isEqualTo("Graphs only");
        assertThat(JsonPath.<Integer>read(response.body(), "$.teacherId"))
                .isEqualTo(owner.getId().intValue());
        assertThat(JsonPath.<String>read(response.body(), "$.enrollCode")).isEqualTo("CODE0001");
        assertThat(JsonPath.<Integer>read(response.body(), "$.id"))
                .isEqualTo(course.getId().intValue());

        Course stored = courses.findById(course.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Advanced Algorithms");
        assertThat(stored.getDescription()).isEqualTo("Graphs only");
        assertThat(stored.getTeacherId()).isEqualTo(owner.getId());
        assertThat(stored.getEnrollCode()).isEqualTo("CODE0001");
        assertThat(stored.getCreatedAt()).isEqualTo(course.getCreatedAt());
    }

    @Test
    void aPutReplacesSoAnOmittedDescriptionIsCleared() throws Exception {
        HttpResponse<String> response = send("PUT", path(), tokenFor(owner), "{\"title\":\"Only a title\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(response.body(), "$.description")).isNull();
        assertThat(courses.findById(course.getId()).orElseThrow().getDescription())
                .isNull();
    }

    @Test
    void theTitleIsTrimmedLikeOnCreate() throws Exception {
        HttpResponse<String> response = send("PUT", path(), tokenFor(owner), "{\"title\":\"  Padded  \"}");

        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Padded");
    }

    @Test
    void anAdminChangesAnyCourseAndTheOwnerStaysTheTeacher() throws Exception {
        HttpResponse<String> response = send("PUT", path(), tokenFor(admin), NEW_VALUES);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.body(), "$.teacherId"))
                .isEqualTo(owner.getId().intValue());
        assertThat(courses.findById(course.getId()).orElseThrow().getTeacherId())
                .isEqualTo(owner.getId());
        assertThat(courses.findById(course.getId()).orElseThrow().getTitle()).isEqualTo("Advanced Algorithms");
    }

    @Test
    void anOwnerOrCodeSentInTheBodyIsIgnored() throws Exception {
        String body = "{\"title\":\"Mine now\",\"teacherId\":" + otherTeacher.getId()
                + ",\"enrollCode\":\"HIJACKED\",\"id\":999}";

        HttpResponse<String> response = send("PUT", path(), tokenFor(owner), body);

        assertThat(response.statusCode()).isEqualTo(200);
        Course stored = courses.findById(course.getId()).orElseThrow();
        assertThat(stored.getTeacherId()).isEqualTo(owner.getId());
        assertThat(stored.getEnrollCode()).isEqualTo("CODE0001");
        assertThat(courses.count()).isEqualTo(1);
    }

    @Test
    void anotherTeacherCannotSeeTheCourseSoCannotChangeIt() throws Exception {
        HttpResponse<String> response = send("PUT", path(), tokenFor(otherTeacher), NEW_VALUES);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
        assertCourseUntouched();
    }

    @Test
    void studentsAreRefusedWhetherOrNotTheyAreEnrolled() throws Exception {
        HttpResponse<String> enrolledStudent = send("PUT", path(), tokenFor(enrolled), NEW_VALUES);
        HttpResponse<String> otherStudent = send("PUT", path(), tokenFor(outsider), NEW_VALUES);

        // The role rule answers before any lookup, so a student learns nothing about the id.
        assertThat(enrolledStudent.statusCode()).isEqualTo(403);
        assertThat(otherStudent.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(enrolledStudent.body(), "$.code")).isEqualTo("ACCESS_DENIED");
        assertCourseUntouched();
    }

    @Test
    void anonymousAndBadTokensAre401() throws Exception {
        assertThat(send("PUT", path(), null, NEW_VALUES).statusCode()).isEqualTo(401);
        assertThat(send("PUT", path(), "not.a.jwt", NEW_VALUES).statusCode()).isEqualTo(401);
        assertCourseUntouched();
    }

    @Test
    void aMissingCourseIs404ForATeacherAndAnAdmin() throws Exception {
        for (User caller : new User[] {owner, admin}) {
            HttpResponse<String> response = send("PUT", "/courses/987654321", tokenFor(caller), NEW_VALUES);

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":null}", "{\"title\":\"\"}", "{\"title\":\"   \"}"})
    void aMissingOrBlankTitleIsRefusedAndNothingChanges(String body) throws Exception {
        HttpResponse<String> response = send("PUT", path(), tokenFor(owner), body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly("title");
        assertCourseUntouched();
    }

    @Test
    void limitsAreTheSameAsOnCreate() throws Exception {
        String tooLongTitle = "{\"title\":\"" + "t".repeat(256) + "\"}";
        String tooLongDescription = "{\"title\":\"A\",\"description\":\"" + "d".repeat(5001) + "\"}";

        assertThat(send("PUT", path(), tokenFor(owner), tooLongTitle).statusCode())
                .isEqualTo(400);
        assertThat(send("PUT", path(), tokenFor(owner), tooLongDescription).statusCode())
                .isEqualTo(400);
        assertThat(send("PUT", path(), tokenFor(owner), "not json").statusCode())
                .isEqualTo(400);
        assertCourseUntouched();
    }

    @Test
    void aStudentSeesTheNewTitleButStillNoCode() throws Exception {
        send("PUT", path(), tokenFor(owner), NEW_VALUES);

        HttpResponse<String> response = get(path(), tokenFor(enrolled));

        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Advanced Algorithms");
        assertThat(response.body()).doesNotContain("enrollCode");
    }

    // === DELETE ================================================================================

    @Test
    void theOwnerDeletesTheCourse() throws Exception {
        HttpResponse<String> response = send("DELETE", path(), tokenFor(owner), null);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(courses.findById(course.getId())).isEmpty();
        assertThat(get(path(), tokenFor(owner)).statusCode()).isEqualTo(404);
    }

    @Test
    void deletingTheCourseAlsoRemovesItsEnrollmentsAndOnlyThose() throws Exception {
        Course other = courses.save(new Course("Compilers", null, owner.getId(), "CODE0002"));
        enrollments.save(new Enrollment(course.getId(), outsider.getId()));
        enrollments.save(new Enrollment(other.getId(), enrolled.getId()));

        assertThat(send("DELETE", path(), tokenFor(owner), null).statusCode()).isEqualTo(204);

        assertThat(enrollments.findAll())
                .extracting(Enrollment::getId)
                .containsExactly(new EnrollmentId(other.getId(), enrolled.getId()));
        assertThat(courses.findAll()).extracting(Course::getId).containsExactly(other.getId());
        // The student simply no longer has the course; their account is untouched.
        assertThat(JsonPath.<List<String>>read(
                        get("/courses", tokenFor(enrolled)).body(), "$.items[*].title"))
                .containsExactly("Compilers");
        assertThat(users.findById(enrolled.getId())).isPresent();
    }

    @Test
    void anAdminDeletesAnyCourse() throws Exception {
        assertThat(send("DELETE", path(), tokenFor(admin), null).statusCode()).isEqualTo(204);

        assertThat(courses.findById(course.getId())).isEmpty();
    }

    @Test
    void anotherTeacherCannotSeeTheCourseSoCannotDeleteIt() throws Exception {
        HttpResponse<String> response = send("DELETE", path(), tokenFor(otherTeacher), null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertCourseUntouched();
        assertThat(enrollments.count()).isEqualTo(1);
    }

    @Test
    void studentsCannotDelete() throws Exception {
        assertThat(send("DELETE", path(), tokenFor(enrolled), null).statusCode())
                .isEqualTo(403);
        assertThat(send("DELETE", path(), tokenFor(outsider), null).statusCode())
                .isEqualTo(403);

        assertCourseUntouched();
        assertThat(enrollments.count()).isEqualTo(1);
    }

    @Test
    void nobodyWithoutAValidTokenCanDelete() throws Exception {
        assertThat(send("DELETE", path(), null, null).statusCode()).isEqualTo(401);
        assertThat(send("DELETE", path(), "not.a.jwt", null).statusCode()).isEqualTo(401);

        assertCourseUntouched();
    }

    @Test
    void deletingTwiceIsA404TheSecondTime() throws Exception {
        assertThat(send("DELETE", path(), tokenFor(owner), null).statusCode()).isEqualTo(204);

        HttpResponse<String> again = send("DELETE", path(), tokenFor(owner), null);

        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(again.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
    }

    @Test
    void aMissingCourseIs404ForATeacherAndAnAdminOnDelete() throws Exception {
        assertThat(send("DELETE", "/courses/987654321", tokenFor(owner), null).statusCode())
                .isEqualTo(404);
        assertThat(send("DELETE", "/courses/987654321", tokenFor(admin), null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void aNonNumericIdIsAMalformedRequest() throws Exception {
        assertThat(send("DELETE", "/courses/abc", tokenFor(owner), null).statusCode())
                .isEqualTo(400);
        assertThat(send("PUT", "/courses/abc", tokenFor(owner), NEW_VALUES).statusCode())
                .isEqualTo(400);
    }

    // --- a course with assignments cannot be deleted -----------------------------------------

    @Test
    void aCourseWithAssignmentsIsNotDeletedAndSaysWhy() throws Exception {
        addAssignment(course);

        HttpResponse<String> response = send("DELETE", path(), tokenFor(owner), null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("COURSE_HAS_ASSIGNMENTS");
        assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("assignments");
        assertCourseUntouched();
    }

    @Test
    void theRefusalLeavesEverythingAsItWasEnrollmentsIncluded() throws Exception {
        addAssignment(course);

        send("DELETE", path(), tokenFor(owner), null);

        // The enrollments were deleted first inside the transaction; the refusal rolled them back.
        assertThat(enrollments.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assignments", Integer.class))
                .isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(
                        get("/courses", tokenFor(enrolled)).body(), "$.items[*].title"))
                .containsExactly("Algorithms");
    }

    @Test
    void anAdminIsRefusedTheSameWay() throws Exception {
        addAssignment(course);

        HttpResponse<String> response = send("DELETE", path(), tokenFor(admin), null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertCourseUntouched();
    }

    @Test
    void onlyTheCourseWithAssignmentsIsBlocked() throws Exception {
        Course empty = courses.save(new Course("Empty", null, owner.getId(), "CODE0002"));
        addAssignment(course);

        assertThat(send("DELETE", "/courses/" + empty.getId(), tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(send("DELETE", path(), tokenFor(owner), null).statusCode()).isEqualTo(409);
    }

    @Test
    void onceTheAssignmentsAreGoneTheCourseCanBeDeleted() throws Exception {
        addAssignment(course);
        assertThat(send("DELETE", path(), tokenFor(owner), null).statusCode()).isEqualTo(409);

        jdbc.update("DELETE FROM assignments");

        assertThat(send("DELETE", path(), tokenFor(owner), null).statusCode()).isEqualTo(204);
        assertThat(courses.findById(course.getId())).isEmpty();
    }

    @Test
    void aCourseWithAssignmentsCanStillBeEdited() throws Exception {
        addAssignment(course);

        assertThat(send("PUT", path(), tokenFor(owner), NEW_VALUES).statusCode())
                .isEqualTo(200);
    }
}
