package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 9.0d (the human's choice over filtering the roster by role): a user who stops being a student
 * loses their enrollments, and only a user whose stored role is {@code STUDENT} may join a course,
 * so enrollments only ever hold students. Submissions stay, as when a student leaves (3.2b).
 */
class RealHttpPromotionEnrollmentTests extends RealHttpTestBase {

    private static final String CODE = "PROMO234";
    private static final String OTHER_CODE = "PROMO567";

    @Autowired
    DataSource dataSource;

    private User admin;
    private User teacher;
    private User ada;
    private User bob;
    private Course course;
    private Course otherCourse;

    @BeforeEach
    void world() {
        admin = saved("root@example.com", Role.ADMIN);
        teacher = saved("teacher@example.com", Role.TEACHER);
        ada = saved("ada@example.com", Role.STUDENT);
        bob = saved("bob@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, teacher.getId(), CODE));
        otherCourse = courses.save(new Course("Graphs", null, teacher.getId(), OTHER_CODE));
        enrollments.save(new Enrollment(course.getId(), ada.getId()));
        enrollments.save(new Enrollment(otherCourse.getId(), ada.getId()));
        enrollments.save(new Enrollment(course.getId(), bob.getId()));
    }

    /** Rows that reference an assignment go before the base class deletes the assignments. */
    @AfterEach
    void removeSubmissions() {
        jdbc.update("delete from submissions");
    }

    // --- helpers -----------------------------------------------------------------------------

    private HttpResponse<String> setRole(User user, String role) throws Exception {
        return patch("/admin/users/" + user.getId() + "/role", "{\"role\":\"" + role + "\"}", tokenFor(admin));
    }

    /** Joins with a token for the user as the object says, so a stale `STUDENT` one after a promotion (2.4b). */
    private HttpResponse<String> join(User asStudent, String code) throws Exception {
        return post("/courses/enroll", "{\"code\":\"" + code + "\"}", tokenFor(asStudent));
    }

    private int enrollmentsOf(User user) {
        return jdbc.queryForObject(
                "select count(*) from enrollments where student_id = ?", Integer.class, user.getId());
    }

    private String roster() throws Exception {
        HttpResponse<String> response = get("/courses/" + course.getId() + "/students", tokenFor(teacher));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    // --- a role change ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEACHER", "ADMIN"})
    void aStudentWhoIsPromotedLeavesEveryCourseAndTheRoster(String role) throws Exception {
        assertThat(roster()).contains("ada@example.com");

        assertThat(setRole(ada, role).statusCode()).isEqualTo(200);

        assertThat(enrollmentsOf(ada)).isZero();
        assertThat(roster()).doesNotContain("ada@example.com").contains("bob@example.com");
        assertThat(enrollmentsOf(bob)).isEqualTo(1);
    }

    @Test
    void thePromotedUsersSubmissionsStay() throws Exception {
        long assignmentId = jdbc.queryForObject(
                "insert into assignments (course_id, title, description, language, deadline, time_limit_ms,"
                        + " memory_limit_mb, published) values (?, 'A', 'd', 'JAVA', now() + interval '1 day',"
                        + " 2000, 256, true) returning id",
                Long.class,
                course.getId());
        jdbc.update(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no)"
                        + " values (?, ?, 'JAVA', 'class Main {}', 1)",
                assignmentId,
                ada.getId());

        assertThat(setRole(ada, "TEACHER").statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject(
                        "select count(*) from submissions where student_id = ?", Integer.class, ada.getId()))
                .isEqualTo(1);
    }

    @Test
    void keepingOrBecomingAStudentKeepsTheEnrollments() throws Exception {
        assertThat(setRole(ada, "STUDENT").statusCode()).isEqualTo(200);
        assertThat(enrollmentsOf(ada)).isEqualTo(2);

        // A teacher demoted to student starts with no courses and joins like anyone else.
        assertThat(setRole(teacher, "STUDENT").statusCode()).isEqualTo(200);
        teacher.setRole(Role.STUDENT);
        assertThat(join(teacher, OTHER_CODE).statusCode()).isEqualTo(200);
        assertThat(enrollmentsOf(teacher)).isEqualTo(1);
    }

    @Test
    void aRefusedRoleChangeKeepsTheEnrollments() throws Exception {
        // A caller who is no longer an admin in the database (7.7e) changes nothing.
        String staleAdmin = tokenFor(admin);
        saved("other-admin@example.com", Role.ADMIN);
        jdbc.update("update users set role = 'TEACHER' where id = ?", admin.getId());

        HttpResponse<String> response =
                patch("/admin/users/" + ada.getId() + "/role", "{\"role\":\"TEACHER\"}", staleAdmin);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(enrollmentsOf(ada)).isEqualTo(2);
    }

    // --- joining afterwards -------------------------------------------------------------------

    @Test
    void aPromotedUserCannotJoinAgainWithTheirOldStudentToken() throws Exception {
        assertThat(setRole(ada, "TEACHER").statusCode()).isEqualTo(200);

        HttpResponse<String> response = join(ada, CODE);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("ACCESS_DENIED");
        assertThat(enrollmentsOf(ada)).isZero();
    }

    @Test
    void aJoinWaitsForAPromotionInFlightAndIsThenRefused() throws Exception {
        Course third = courses.save(new Course("Trees", null, teacher.getId(), "PROMO789"));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // What a promotion holds until it commits: the user's row, updated.
            try (var promote = connection.prepareStatement("update users set role = 'TEACHER' where id = ?")) {
                promote.setLong(1, ada.getId());
                promote.executeUpdate();

                CompletableFuture<HttpResponse<String>> joining = CompletableFuture.supplyAsync(() -> {
                    try {
                        return join(ada, "PROMO789");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(500);
                assertThat(joining).isNotDone();

                connection.commit();
                HttpResponse<String> response = joining.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(code(response)).isEqualTo("ACCESS_DENIED");
            }
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from enrollments where course_id = ?", Integer.class, third.getId()))
                .isZero();
    }

    @Test
    void aPromotionWaitsForAJoinInFlightAndThenRemovesIt() throws Exception {
        Course third = courses.save(new Course("Trees", null, teacher.getId(), "PROMO789"));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // What a join holds until it commits: the role read FOR SHARE, then the new enrollment.
            try (var lock = connection.prepareStatement("select role from users where id = ? for share");
                    var insert = connection.prepareStatement(
                            "insert into enrollments (course_id, student_id) values (?, ?)")) {
                lock.setLong(1, ada.getId());
                lock.executeQuery().close();
                insert.setLong(1, third.getId());
                insert.setLong(2, ada.getId());
                insert.executeUpdate();

                CompletableFuture<HttpResponse<String>> promotion = CompletableFuture.supplyAsync(() -> {
                    try {
                        return setRole(ada, "TEACHER");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(500);
                assertThat(promotion).isNotDone();

                connection.commit();
                assertThat(promotion.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            }
        }
        assertThat(enrollmentsOf(ada)).isZero();
    }
}
