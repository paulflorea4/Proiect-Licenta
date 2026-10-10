package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 3.6b: joining a course is idempotent. {@link RealHttpEnrollTests} covers the repeat, the spelling
 * of the code and the simultaneous joins; this adds what a repeat must <i>not</i> change.
 */
class RealHttpEnrollIdempotencyTests extends RealHttpTestBase {

    private User teacher;
    private User student;
    private User classmate;
    private Course course;

    @BeforeEach
    void world() {
        teacher = saved("teacher@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, teacher.getId(), "ABCD2345"));
    }

    private HttpResponse<String> join(User as) throws Exception {
        return post("/courses/enroll", "{\"code\":\"ABCD2345\"}", tokenFor(as));
    }

    private OffsetDateTime enrolledAt(User of) {
        return jdbc.queryForObject(
                "select enrolled_at from enrollments where course_id = ? and student_id = ?",
                OffsetDateTime.class,
                course.getId(),
                of.getId());
    }

    private int enrollmentRows() {
        return jdbc.queryForObject("select count(*) from enrollments", Integer.class);
    }

    @Test
    void aRepeatedJoinKeepsTheOriginalEnrollmentTime() throws Exception {
        join(student);
        OffsetDateTime first = enrolledAt(student);
        Thread.sleep(50);

        join(student);
        join(student);

        assertThat(enrolledAt(student)).isEqualTo(first);
    }

    @Test
    void theStudentIsOnTheRosterAndInTheirListExactlyOnceHoweverOftenTheyJoin() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(join(student).statusCode()).isEqualTo(200);
        }

        HttpResponse<String> roster = get("/courses/" + course.getId() + "/students", tokenFor(teacher));
        HttpResponse<String> mine = get("/courses", tokenFor(student));

        assertThat(JsonPath.<List<Integer>>read(roster.body(), "$.items[*].id"))
                .containsExactly(student.getId().intValue());
        assertThat(JsonPath.<Integer>read(roster.body(), "$.totalElements")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(mine.body(), "$.totalElements")).isEqualTo(1);
        assertThat(enrollmentRows()).isEqualTo(1);
    }

    @Test
    void aRepeatedJoinByOneStudentDoesNotTouchAClassmatesEnrollment() throws Exception {
        join(classmate);
        OffsetDateTime classmateSince = enrolledAt(classmate);

        join(student);
        join(student);

        assertThat(enrolledAt(classmate)).isEqualTo(classmateSince);
        assertThat(enrollmentRows()).isEqualTo(2);
    }

    @Test
    void everyRepeatReturnsTheSameBodyAsTheFirstJoin() throws Exception {
        String first = join(student).body();

        for (int i = 0; i < 3; i++) {
            assertThat(join(student).body()).isEqualTo(first);
        }
    }

    @Test
    void leavingOnceAndJoiningAgainIsANewEnrollmentNotAnError() throws Exception {
        join(student);
        assertThat(send("DELETE", "/courses/" + course.getId() + "/enrollment", tokenFor(student), null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(enrollmentRows()).isZero();

        assertThat(join(student).statusCode()).isEqualTo(200);
        assertThat(join(student).statusCode()).isEqualTo(200);

        assertThat(enrollmentRows()).isEqualTo(1);
    }

    @Test
    void aRepeatedJoinNeverShowsTheEnrollmentCodeToTheStudent() throws Exception {
        join(student);

        assertThat(join(student).body()).doesNotContain("enrollCode").doesNotContain("ABCD2345");
    }
}
