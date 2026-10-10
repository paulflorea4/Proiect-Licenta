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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 3.3b: `GET /courses/{id}/assignments` and `GET /assignments/{id}`. */
class RealHttpAssignmentGetTests extends RealHttpTestBase {

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private User outsider;
    private Course course;
    private Course otherCourse;
    private Assignment draft;
    private Assignment published;
    private Assignment otherCoursePublished;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        otherCourse = courses.save(new Course("Databases", null, otherTeacher.getId(), "WXYZ2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        draft = assignment(course, "Draft", false);
        published = assignment(course, "Sorting", true);
        otherCoursePublished = assignment(otherCourse, "Joins", true);
    }

    private Assignment assignment(Course in, String title, boolean isPublished) {
        Assignment a = assignments.save(new Assignment(
                in.getId(),
                title,
                "Do " + title,
                "JAVA",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                3,
                2000,
                256,
                "class Main {}"));
        if (isPublished) {
            publish(a.getId());
        }
        return a;
    }

    private void publish(long id) {
        jdbc.update("update assignments set published = true where id = ?", id);
    }

    private HttpResponse<String> list(User as, Course of, String query) throws Exception {
        return get("/courses/" + of.getId() + "/assignments" + query, tokenFor(as));
    }

    private HttpResponse<String> one(User as, long id) throws Exception {
        return get("/assignments/" + id, as == null ? null : tokenFor(as));
    }

    private static List<Integer> ids(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.items[*].id");
    }

    // --- the list ----------------------------------------------------------------------------

    @Test
    void theOwnerSeesDraftsAndPublishedInIdOrder() throws Exception {
        HttpResponse<String> response = list(owner, course, "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ids(response))
                .containsExactly(draft.getId().intValue(), published.getId().intValue());
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.items[0].published"))
                .isFalse();
        assertThat(JsonPath.<Boolean>read(response.body(), "$.items[1].published"))
                .isTrue();
        assertThat(JsonPath.<String>read(response.body(), "$.items[1].createdAt"))
                .isNotBlank();
    }

    @Test
    void anAdminSeesDraftsToo() throws Exception {
        assertThat(ids(list(admin, course, "")))
                .containsExactly(draft.getId().intValue(), published.getId().intValue());
    }

    @Test
    void anEnrolledStudentSeesOnlyPublishedOnes() throws Exception {
        HttpResponse<String> response = list(student, course, "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ids(response)).containsExactly(published.getId().intValue());
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isEqualTo(1);
    }

    @Test
    void aStudentsShapeHasNoDraftStateOrTimestamps() throws Exception {
        HttpResponse<String> response = list(student, course, "");

        assertThat(JsonPath.<String>read(response.body(), "$.items[0].title")).isEqualTo("Sorting");
        assertThat(JsonPath.<String>read(response.body(), "$.items[0].starterCode"))
                .isEqualTo("class Main {}");
        assertThat(response.body()).doesNotContain("\"published\"", "\"createdAt\"", "\"updatedAt\"");
    }

    @Test
    void anUnlimitedAttemptsAssignmentStillShowsNullAttemptsToStudents() throws Exception {
        Assignment unlimited = assignments.save(new Assignment(
                course.getId(), "Free", "d", "PYTHON", Instant.now().plusSeconds(3600), null, 1000, 64, null));
        publish(unlimited.getId());

        HttpResponse<String> response = one(student, unlimited.getId());

        assertThat(response.body()).contains("\"maxAttempts\":null");
    }

    @Test
    void aCourseWithNoAssignmentsGivesAnEmptyPage() throws Exception {
        Course empty = courses.save(new Course("Empty", null, owner.getId(), "EMPT2345"));

        HttpResponse<String> response = list(owner, empty, "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ids(response)).isEmpty();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isZero();
    }

    @Test
    void theListOnlyHoldsTheAssignmentsOfThatCourse() throws Exception {
        assertThat(ids(list(owner, course, "")))
                .doesNotContain(otherCoursePublished.getId().intValue());
        assertThat(ids(list(otherTeacher, otherCourse, "")))
                .containsExactly(otherCoursePublished.getId().intValue());
    }

    @Test
    void theListIsPaginated() throws Exception {
        HttpResponse<String> first = list(owner, course, "?page=0&size=1");
        HttpResponse<String> second = list(owner, course, "?page=1&size=1");
        HttpResponse<String> beyond = list(owner, course, "?page=5&size=1");

        assertThat(ids(first)).containsExactly(draft.getId().intValue());
        assertThat(ids(second)).containsExactly(published.getId().intValue());
        assertThat(JsonPath.<Integer>read(first.body(), "$.totalPages")).isEqualTo(2);
        assertThat(beyond.statusCode()).isEqualTo(200);
        assertThat(ids(beyond)).isEmpty();
        assertThat(JsonPath.<Integer>read(beyond.body(), "$.totalElements")).isEqualTo(2);
    }

    @Test
    void aBadPageParameterIsA400() throws Exception {
        assertThat(list(owner, course, "?page=-1").statusCode()).isEqualTo(400);
        assertThat(list(owner, course, "?size=0").statusCode()).isEqualTo(400);
    }

    @Test
    void aCourseTheCallerCannotSeeIsTheSame404AsAMissingOne() throws Exception {
        HttpResponse<String> otherTeachers = list(otherTeacher, course, "");
        HttpResponse<String> notEnrolled = list(outsider, course, "");
        HttpResponse<String> missing = get("/courses/987654321/assignments", tokenFor(otherTeacher));

        assertThat(otherTeachers.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(otherTeachers.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
        assertThat(notEnrolled.body()).isEqualTo(missing.body());
        assertThat(otherTeachers.body()).isEqualTo(missing.body());
    }

    @Test
    void aStudentWhoLeftSeesNothingMore() throws Exception {
        enrollments.deleteAll();

        assertThat(list(student, course, "").statusCode()).isEqualTo(404);
    }

    @Test
    void anAnonymousCallerIsA401() throws Exception {
        assertThat(get("/courses/" + course.getId() + "/assignments", null).statusCode())
                .isEqualTo(401);
    }

    // --- one assignment ----------------------------------------------------------------------

    @Test
    void theOwnerReadsADraftInFull() throws Exception {
        HttpResponse<String> response = one(owner, draft.getId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Draft");
        assertThat(JsonPath.<Integer>read(response.body(), "$.courseId"))
                .isEqualTo(course.getId().intValue());
        assertThat(JsonPath.<Boolean>read(response.body(), "$.published")).isFalse();
        assertThat(JsonPath.<String>read(response.body(), "$.language")).isEqualTo("JAVA");
        assertThat(JsonPath.<Integer>read(response.body(), "$.timeLimitMs")).isEqualTo(2000);
        assertThat(JsonPath.<String>read(response.body(), "$.updatedAt")).isNotBlank();
    }

    @Test
    void anAdminReadsADraft() throws Exception {
        assertThat(one(admin, draft.getId()).statusCode()).isEqualTo(200);
    }

    @Test
    void anEnrolledStudentReadsAPublishedAssignmentWithoutDraftState() throws Exception {
        HttpResponse<String> response = one(student, published.getId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Sorting");
        assertThat(JsonPath.<String>read(response.body(), "$.description")).isEqualTo("Do Sorting");
        assertThat(JsonPath.<String>read(response.body(), "$.starterCode")).isEqualTo("class Main {}");
        assertThat(response.body()).doesNotContain("\"published\"", "\"createdAt\"", "\"updatedAt\"");
    }

    @Test
    void aStudentGetsTheSame404ForADraftAsForAMissingId() throws Exception {
        HttpResponse<String> forDraft = one(student, draft.getId());
        HttpResponse<String> forMissing = one(student, 987654321L);

        assertThat(forDraft.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(forDraft.body(), "$.code")).isEqualTo("ASSIGNMENT_NOT_FOUND");
        assertThat(forDraft.body()).isEqualTo(forMissing.body());
    }

    @Test
    void anUnenrolledStudentCannotReadEvenAPublishedAssignment() throws Exception {
        HttpResponse<String> response = one(outsider, published.getId());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(one(outsider, 987654321L).body());
    }

    @Test
    void anotherTeacherCannotReadAssignmentsOfACourseTheyDoNotOwn() throws Exception {
        assertThat(one(otherTeacher, draft.getId()).statusCode()).isEqualTo(404);
        assertThat(one(otherTeacher, published.getId()).statusCode()).isEqualTo(404);
        assertThat(one(otherTeacher, otherCoursePublished.getId()).statusCode()).isEqualTo(200);
    }

    @Test
    void aStudentEnrolledElsewhereCannotReadThisCoursesAssignment() throws Exception {
        enrollments.save(new Enrollment(otherCourse.getId(), outsider.getId()));

        assertThat(one(outsider, published.getId()).statusCode()).isEqualTo(404);
        assertThat(one(outsider, otherCoursePublished.getId()).statusCode()).isEqualTo(200);
    }

    @Test
    void anAnonymousCallerIsA401ForOneAssignment() throws Exception {
        assertThat(one(null, published.getId()).statusCode()).isEqualTo(401);
    }

    @Test
    void aNonNumericIdIsA400() throws Exception {
        assertThat(get("/assignments/abc", tokenFor(owner)).statusCode()).isEqualTo(400);
    }

    @Test
    void publishingMakesADraftVisibleToTheStudent() throws Exception {
        assertThat(one(student, draft.getId()).statusCode()).isEqualTo(404);

        publish(draft.getId());

        assertThat(one(student, draft.getId()).statusCode()).isEqualTo(200);
        assertThat(ids(list(student, course, "")))
                .containsExactly(draft.getId().intValue(), published.getId().intValue());
    }
}
