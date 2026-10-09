package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.1b: `GET /courses`, one endpoint with a different answer for each role. */
class RealHttpCourseListTests extends RealHttpTestBase {

    private int codeCounter;

    private Course course(String title, User teacher) {
        return courses.save(new Course(title, null, teacher.getId(), String.format("CODE%04d", ++codeCounter)));
    }

    private void enroll(User student, Course course) {
        enrollments.save(new Enrollment(course.getId(), student.getId()));
    }

    private List<String> titles(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.items[*].title");
    }

    // --- teacher: their own ------------------------------------------------------------------

    @Test
    void aTeacherSeesTheirOwnCoursesWithTheCodeAndNobodyElses() throws Exception {
        User ada = saved("ada@example.com", Role.TEACHER);
        User grace = saved("grace@example.com", Role.TEACHER);
        Course mine = course("Algorithms", ada);
        course("Compilers", grace);
        course("Databases", ada);

        HttpResponse<String> response = get("/courses", tokenFor(ada));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(titles(response)).containsExactly("Algorithms", "Databases");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].enrollCode"))
                .containsExactly(mine.getEnrollCode(), "CODE0003");
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isEqualTo(2);
    }

    @Test
    void aTeacherWithoutCoursesGetsAnEmptyPage() throws Exception {
        User ada = saved("ada@example.com", Role.TEACHER);
        course("Someone else's", saved("grace@example.com", Role.TEACHER));

        HttpResponse<String> response = get("/courses", tokenFor(ada));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(titles(response)).isEmpty();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isZero();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isZero();
    }

    @Test
    void aTeacherEnrolledSomewhereElseStillSeesOnlyWhatTheyOwn() throws Exception {
        // The role decides which list: a teacher's list is the courses they own, nothing more.
        User ada = saved("ada@example.com", Role.TEACHER);
        User grace = saved("grace@example.com", Role.TEACHER);
        course("Algorithms", ada);
        enroll(ada, course("Compilers", grace));

        assertThat(titles(get("/courses", tokenFor(ada)))).containsExactly("Algorithms");
    }

    // --- student: the ones they joined -------------------------------------------------------

    @Test
    void aStudentSeesOnlyTheCoursesTheyAreEnrolledIn() throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        User bob = saved("bob@example.com", Role.STUDENT);
        User eve = saved("eve@example.com", Role.STUDENT);
        Course algorithms = course("Algorithms", teacher);
        Course compilers = course("Compilers", teacher);
        course("Databases", teacher); // nobody is in it
        enroll(bob, algorithms);
        enroll(bob, compilers);
        enroll(eve, compilers);

        assertThat(titles(get("/courses", tokenFor(bob)))).containsExactly("Algorithms", "Compilers");
        assertThat(titles(get("/courses", tokenFor(eve)))).containsExactly("Compilers");
    }

    @Test
    void aStudentWhoJoinedNothingSeesNothing() throws Exception {
        course("Algorithms", saved("teacher@example.com", Role.TEACHER));

        HttpResponse<String> response = get("/courses", tokenFor(saved("bob@example.com", Role.STUDENT)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(titles(response)).isEmpty();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isZero();
    }

    @Test
    void aCourseWithManyStudentsAppearsOnceInEachStudentsList() throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        User bob = saved("bob@example.com", Role.STUDENT);
        Course algorithms = course("Algorithms", teacher);
        for (int i = 0; i < 5; i++) {
            enroll(saved("student" + i + "@example.com", Role.STUDENT), algorithms);
        }
        enroll(bob, algorithms);

        HttpResponse<String> response = get("/courses", tokenFor(bob));

        assertThat(titles(response)).containsExactly("Algorithms");
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isEqualTo(1);
    }

    @Test
    void aStudentNeverReceivesTheEnrollmentCode() throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        User bob = saved("bob@example.com", Role.STUDENT);
        Course algorithms = course("Algorithms", teacher);
        enroll(bob, algorithms);

        HttpResponse<String> response = get("/courses", tokenFor(bob));

        // The key is absent, not null, and the code appears nowhere in the raw body.
        assertThat(response.body()).doesNotContain("enrollCode").doesNotContain(algorithms.getEnrollCode());
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$.items[0]")
                        .keySet())
                .containsExactlyInAnyOrder("id", "title", "description", "teacherId", "createdAt");
    }

    @Test
    void aStudentCannotAskForSomeoneElsesList() throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        User bob = saved("bob@example.com", Role.STUDENT);
        User eve = saved("eve@example.com", Role.STUDENT);
        enroll(eve, course("Eves course", teacher));
        enroll(bob, course("Bobs course", teacher));

        // Parameters that would name another user or owner are not read.
        for (String query :
                List.of("?studentId=" + eve.getId(), "?userId=" + eve.getId(), "?teacherId=" + teacher.getId())) {
            assertThat(titles(get("/courses" + query, tokenFor(bob)))).containsExactly("Bobs course");
        }
    }

    // --- admin: everything -------------------------------------------------------------------

    @Test
    void anAdminSeesEveryCourseWithItsCode() throws Exception {
        course("Algorithms", saved("ada@example.com", Role.TEACHER));
        course("Compilers", saved("grace@example.com", Role.TEACHER));

        HttpResponse<String> response = get("/courses", tokenFor(saved("root@example.com", Role.ADMIN)));

        assertThat(titles(response)).containsExactly("Algorithms", "Compilers");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].enrollCode"))
                .containsExactly("CODE0001", "CODE0002");
    }

    // --- pagination --------------------------------------------------------------------------

    @Test
    void pagesFollowIdOrderWithoutOverlapOrGaps() throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            expected.add(course("Course " + i, teacher).getTitle());
        }
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            HttpResponse<String> response = get("/courses?page=" + page + "&size=10", admin);
            assertThat(JsonPath.<Integer>read(response.body(), "$.page")).isEqualTo(page);
            assertThat(JsonPath.<Integer>read(response.body(), "$.size")).isEqualTo(10);
            assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements"))
                    .isEqualTo(25);
            assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(3);
            seen.addAll(titles(response));
        }

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(new HashSet<>(seen)).hasSize(25);
    }

    @Test
    void theTotalsOfATeachersListCountOnlyTheirCourses() throws Exception {
        User ada = saved("ada@example.com", Role.TEACHER);
        User grace = saved("grace@example.com", Role.TEACHER);
        for (int i = 0; i < 3; i++) {
            course("Ada " + i, ada);
            course("Grace " + i, grace);
        }

        HttpResponse<String> response = get("/courses?size=2", tokenFor(ada));

        assertThat(titles(response)).containsExactly("Ada 0", "Ada 1");
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalPages")).isEqualTo(2);
        assertThat(titles(get("/courses?size=2&page=1", tokenFor(ada)))).containsExactly("Ada 2");
    }

    @Test
    void theDefaultPageIsTheFirstTwentyAndAPageBeyondTheEndIsEmpty() throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        for (int i = 0; i < 21; i++) {
            course("Course " + i, teacher);
        }
        String token = tokenFor(teacher);

        assertThat(titles(get("/courses", token))).hasSize(20);
        HttpResponse<String> beyond = get("/courses?page=5", token);
        assertThat(beyond.statusCode()).isEqualTo(200);
        assertThat(titles(beyond)).isEmpty();
        assertThat(JsonPath.<Integer>read(beyond.body(), "$.totalElements")).isEqualTo(21);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=-5", "page=abc", "size=abc", "page=2147483647&size=100"})
    void badPagingParametersAreRefused(String query) throws Exception {
        HttpResponse<String> response = get("/courses?" + query, tokenFor(saved("ada@example.com", Role.TEACHER)));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    // --- who may call ------------------------------------------------------------------------

    @Test
    void nobodyWithoutAValidTokenGetsAList() throws Exception {
        course("Algorithms", saved("ada@example.com", Role.TEACHER));

        assertThat(get("/courses", null).statusCode()).isEqualTo(401);
        assertThat(get("/courses", "not.a.jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void joiningDoesNotPutTheCourseInAClassmatesList() throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        User bob = saved("bob@example.com", Role.STUDENT);
        User eve = saved("eve@example.com", Role.STUDENT);

        enroll(bob, course("Algorithms", teacher));

        assertThat(titles(get("/courses", tokenFor(bob)))).containsExactly("Algorithms");
        assertThat(titles(get("/courses", tokenFor(eve)))).isEmpty();
    }
}
