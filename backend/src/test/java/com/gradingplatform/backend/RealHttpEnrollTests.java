package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.EnrollmentId;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.2a: `POST /courses/enroll`. */
class RealHttpEnrollTests extends RealHttpTestBase {

    private static final String CODE = "ABCD2345";

    private User teacher;
    private User bob;
    private Course course;

    @BeforeEach
    void world() {
        teacher = saved("teacher@example.com", Role.TEACHER);
        bob = saved("bob@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", "Sorting and graphs", teacher.getId(), CODE));
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private HttpResponse<String> join(User student, String code) throws Exception {
        return post("/courses/enroll", body(code), tokenFor(student));
    }

    // --- joining -----------------------------------------------------------------------------

    @Test
    void aStudentJoinsWithTheCodeAndGetsTheCourseWithoutIt() throws Exception {
        HttpResponse<String> response = join(bob, CODE);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.body(), "$.id"))
                .isEqualTo(course.getId().intValue());
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Algorithms");
        assertThat(JsonPath.<Integer>read(response.body(), "$.teacherId"))
                .isEqualTo(teacher.getId().intValue());
        // The student is in; the code is still the teacher's to hand out.
        assertThat(response.body()).doesNotContain("enrollCode").doesNotContain(CODE);
        assertThat(enrollments.findAll())
                .extracting(e -> e.getId())
                .containsExactly(new EnrollmentId(course.getId(), bob.getId()));
    }

    @Test
    void afterJoiningTheCourseIsInTheListAndReadable() throws Exception {
        join(bob, CODE);

        assertThat(JsonPath.<List<String>>read(get("/courses", tokenFor(bob)).body(), "$.items[*].title"))
                .containsExactly("Algorithms");
        assertThat(get("/courses/" + course.getId(), tokenFor(bob)).statusCode())
                .isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd2345", "AbCd2345", "  ABCD2345", "ABCD2345  ", "\\t ABCD2345\\n"})
    void caseAndSurroundingSpacesDoNotMatter(String typed) throws Exception {
        HttpResponse<String> response = join(bob, typed);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(enrollments.count()).isEqualTo(1);
    }

    @Test
    void severalStudentsJoinTheSameCourse() throws Exception {
        User eve = saved("eve@example.com", Role.STUDENT);

        assertThat(join(bob, CODE).statusCode()).isEqualTo(200);
        assertThat(join(eve, CODE).statusCode()).isEqualTo(200);

        assertThat(enrollments.count()).isEqualTo(2);
    }

    @Test
    void joiningOneCourseDoesNotTouchTheOthers() throws Exception {
        Course other = courses.save(new Course("Compilers", null, teacher.getId(), "WXYZ6789"));

        join(bob, CODE);

        assertThat(enrollments.findAll())
                .extracting(e -> e.getCourseId())
                .containsExactly(course.getId())
                .doesNotContain(other.getId());
    }

    // --- joining twice is not an error -------------------------------------------------------

    @Test
    void joiningTwiceGivesTheSameAnswerAndOneEnrollment() throws Exception {
        HttpResponse<String> first = join(bob, CODE);
        HttpResponse<String> second = join(bob, CODE);

        assertThat(second.statusCode()).isEqualTo(200).isEqualTo(first.statusCode());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(enrollments.count()).isEqualTo(1);
    }

    @Test
    void joiningAgainWithAnotherSpellingOfTheCodeIsStillTheSameEnrollment() throws Exception {
        join(bob, CODE);

        assertThat(join(bob, "abcd2345").statusCode()).isEqualTo(200);

        assertThat(enrollments.count()).isEqualTo(1);
    }

    @Test
    void manySimultaneousJoinsByOneStudentAllSucceedWithOneEnrollment() throws Exception {
        int callers = 8;
        for (int round = 0; round < 3; round++) {
            enrollments.deleteAll();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                String token = tokenFor(bob);
                for (int i = 0; i < callers; i++) {
                    results.add(pool.submit(() -> {
                        go.await();
                        return post("/courses/enroll", body(CODE), token).statusCode();
                    }));
                }
                go.countDown();
                for (Future<Integer> result : results) {
                    assertThat(result.get()).isEqualTo(200);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(enrollments.count()).isEqualTo(1);
        }
    }

    @Test
    void simultaneousJoinsByDifferentStudentsAllGetIn() throws Exception {
        List<User> students = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            students.add(saved("student" + i + "@example.com", Role.STUDENT));
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(students.size());
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (User student : students) {
                String token = tokenFor(student);
                results.add(pool.submit(() -> {
                    go.await();
                    return post("/courses/enroll", body(CODE), token).statusCode();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(enrollments.count()).isEqualTo(students.size());
    }

    // --- a code that matches nothing ---------------------------------------------------------

    @Test
    void anUnknownCodeIsA404AndJoinsNothing() throws Exception {
        HttpResponse<String> response = join(bob, "ZZZZZZZZ");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("ENROLL_CODE_NOT_FOUND");
        assertThat(enrollments.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCD234", "ABCD23456", "ABCD2346", "BCD2345", "0BCD2345", "ABCD 2345", "ABCD-2345"})
    void aCodeThatIsOnlyNearlyRightIsJustUnknown(String nearlyRight) throws Exception {
        HttpResponse<String> response = join(bob, nearlyRight);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("ENROLL_CODE_NOT_FOUND");
        assertThat(response.body()).doesNotContain("Algorithms");
        assertThat(enrollments.count()).isZero();
    }

    @Test
    void everyUnknownCodeGetsTheIdenticalAnswer() throws Exception {
        HttpResponse<String> a = join(bob, "ZZZZZZZZ");
        HttpResponse<String> b = join(bob, "ABCD2346");

        assertThat(a.statusCode()).isEqualTo(404).isEqualTo(b.statusCode());
        assertThat(a.body()).isEqualTo(b.body());
    }

    @Test
    void theCodeOfADeletedCourseNoLongerWorks() throws Exception {
        assertThat(send("DELETE", "/courses/" + course.getId(), tokenFor(teacher), null)
                        .statusCode())
                .isEqualTo(204);

        assertThat(join(bob, CODE).statusCode()).isEqualTo(404);
    }

    @Test
    void aFailedAttemptByAnotherStudentChangesNothingForAnEnrolledOne() throws Exception {
        join(bob, CODE);

        // A different student's failed attempt changes nothing for bob.
        join(saved("eve@example.com", Role.STUDENT), "ZZZZZZZZ");

        assertThat(enrollments.count()).isEqualTo(1);
        assertThat(get("/courses/" + course.getId(), tokenFor(bob)).statusCode())
                .isEqualTo(200);
    }

    // --- invalid input -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"code\":null}", "{\"code\":\"\"}", "{\"code\":\"   \"}"})
    void aMissingOrBlankCodeIsRefused(String body) throws Exception {
        HttpResponse<String> response = post("/courses/enroll", body, tokenFor(bob));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly("code");
        assertThat(enrollments.count()).isZero();
    }

    @Test
    void aCodeLongerThanAnyCodeCanBeIsMalformedNotMerelyUnknown() throws Exception {
        HttpResponse<String> response = join(bob, "A".repeat(21));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("AAAAAAAA"); // the rejected value is not echoed
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "{\"code\":{\"a\":1}}", "{\"code\":["})
    void aBodyThatIsNotACodeIsAMalformedRequest(String body) throws Exception {
        assertThat(post("/courses/enroll", body, tokenFor(bob)).statusCode()).isEqualTo(400);
    }

    @Test
    void theCodeIsNotPrintedWhenTheRequestIsLogged() {
        assertThat(new com.gradingplatform.backend.dto.EnrollRequest(CODE).toString())
                .doesNotContain(CODE);
    }

    // --- who may call ------------------------------------------------------------------------

    @Test
    void teachersAndAdminsCannotJoin() throws Exception {
        HttpResponse<String> asTeacher = join(teacher, CODE);
        HttpResponse<String> asAdmin = join(saved("root@example.com", Role.ADMIN), CODE);

        assertThat(asTeacher.statusCode()).isEqualTo(403);
        assertThat(asAdmin.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(asTeacher.body(), "$.code")).isEqualTo("ACCESS_DENIED");
        // Refused by the role, whether or not the code is right: it is not a way to test codes.
        assertThat(join(teacher, "ZZZZZZZZ").statusCode()).isEqualTo(403);
        assertThat(enrollments.count()).isZero();
    }

    @Test
    void nobodyWithoutAValidTokenCanJoin() throws Exception {
        assertThat(post("/courses/enroll", body(CODE), null).statusCode()).isEqualTo(401);
        assertThat(post("/courses/enroll", body(CODE), "not.a.jwt").statusCode())
                .isEqualTo(401);
        assertThat(enrollments.count()).isZero();
    }

    @Test
    void aStudentWhoseAccountWasDeletedIsToldToSignInAgain() throws Exception {
        String token = tokenFor(bob);
        users.delete(bob);

        HttpResponse<String> response = post("/courses/enroll", body(CODE), token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("ACCOUNT_NO_LONGER_EXISTS");
        assertThat(enrollments.count()).isZero();
    }

    @Test
    void theEnrollPathIsNotMistakenForACourseId() throws Exception {
        // GET /courses/enroll would be GET /courses/{id} with id "enroll": a malformed id, not a join.
        HttpResponse<String> response = get("/courses/enroll", tokenFor(bob));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$").keySet())
                .contains("code", "status", "message");
        assertThat(enrollments.count()).isZero();
    }
}
