package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 3.6b: a hidden test's content must never be in any response a student can get. The masked list
 * is checked on its own in {@link RealHttpStudentTestViewTests}; this sweeps <b>every</b> GET
 * endpoint Spring has mapped, so an endpoint added in a later phase (a result page, a submission,
 * an AI feedback request) is put under the same check automatically. Three marker strings occur
 * only inside one hidden test (its name, input and expected output), so they can be searched for in
 * raw response bodies of every status.
 *
 * <p>If this test fails with "teach the sweep about {name}", a new endpoint has a path variable the
 * sweep cannot fill in; add it to {@link #resolve}.
 */
class RealHttpHiddenTestSweepTests extends RealHttpTestBase {

    private static final String[] MARKERS = {"MARK-NAME-9f3a", "MARK-INPUT-9f3a", "MARK-OUTPUT-9f3a"};

    /**
     * Strings only in the student's result for the hidden test (5.5a): what their program printed on
     * it and its stderr, which may echo the hidden input. Only the people who run the course may see
     * them; the positive control for these comes with their view of a submission (5.5b).
     */
    private static final String[] RESULT_MARKERS = {"MARK-ACTUAL-9f3a", "MARK-STDERR-9f3a"};

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private User outsider;
    private Course course;
    private Assignment assignment;
    private RubricCriterion criterion;
    private TestCase hidden;
    private long submission;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "Sort the numbers",
                "JAVA",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                "class Main {}"));
        criterion = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 100));
        TestCase visible = testCases.save(
                new TestCase(assignment.getId(), criterion.getId(), "visible", "1", "1", TestVisibility.PUBLIC, 1, 1));
        hidden = testCases.save(new TestCase(
                assignment.getId(),
                criterion.getId(),
                MARKERS[0],
                MARKERS[1],
                MARKERS[2],
                TestVisibility.HIDDEN,
                1,
                2));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
        // The enrolled student's graded submission, with a result for each test (5.5a).
        submission = jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'JAVA', 'class Main {}', 1, 'GRADED') returning id",
                Long.class,
                assignment.getId(),
                student.getId());
        jdbc.update(
                "insert into test_results (submission_id, test_case_id, status, actual_output, stderr, runtime_ms)"
                        + " values (?, ?, 'PASSED', '1', '', 5), (?, ?, 'FAILED', ?, ?, 6)",
                submission,
                visible.getId(),
                submission,
                hidden.getId(),
                RESULT_MARKERS[0],
                RESULT_MARKERS[1]);
        jdbc.update("insert into grades (submission_id, total_score, max_score) values (?, 50.00, 100.00)", submission);
    }

    /** Fills a path pattern with the ids of the world; fails loudly on a variable it does not know. */
    private String resolve(String pattern) {
        String path = pattern;
        if (path.contains("{id}")) {
            long id;
            if (path.startsWith("/courses/")) {
                id = course.getId();
            } else if (path.startsWith("/assignments/")) {
                id = assignment.getId();
            } else if (path.startsWith("/submissions/")) {
                id = submission;
            } else if (path.startsWith("/admin/users/")) {
                id = student.getId();
            } else {
                throw new AssertionError("teach the sweep about {id} in " + pattern);
            }
            path = path.replace("{id}", String.valueOf(id));
        }
        path = path.replace("{courseId}", String.valueOf(course.getId()))
                .replace("{assignmentId}", String.valueOf(assignment.getId()))
                .replace("{criterionId}", String.valueOf(criterion.getId()))
                .replace("{testId}", String.valueOf(hidden.getId()))
                .replace("{submissionId}", String.valueOf(submission));
        if (path.contains("{")) {
            throw new AssertionError("teach the sweep about the path variable in " + pattern);
        }
        return path;
    }

    private Set<String> getPatterns() {
        Set<String> patterns = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
                patterns.addAll(info.getPathPatternsCondition().getPatternValues());
            }
        });
        patterns.remove("/error");
        return patterns;
    }

    @Test
    void noGetEndpointShowsAHiddenTestToAnyoneWhoDoesNotRunTheCourse() throws Exception {
        Map<String, User> callers = new LinkedHashMap<>();
        callers.put("enrolled student", student);
        callers.put("student who is not enrolled", outsider);
        callers.put("another teacher", otherTeacher);

        for (String pattern : getPatterns()) {
            String path = resolve(pattern);
            for (var caller : callers.entrySet()) {
                HttpResponse<String> response = get(path, tokenFor(caller.getValue()));
                for (String marker : allMarkers()) {
                    assertThat(response.body())
                            .as(
                                    "GET %s as %s (status %d) leaks %s",
                                    pattern, caller.getKey(), response.statusCode(), marker)
                            .doesNotContain(marker);
                }
            }
            HttpResponse<String> anonymous = get(path, null);
            for (String marker : allMarkers()) {
                assertThat(anonymous.body())
                        .as("GET %s as nobody leaks %s", pattern, marker)
                        .doesNotContain(marker);
            }
        }
    }

    private static String[] allMarkers() {
        String[] all = Arrays.copyOf(MARKERS, MARKERS.length + RESULT_MARKERS.length);
        System.arraycopy(RESULT_MARKERS, 0, all, MARKERS.length, RESULT_MARKERS.length);
        return all;
    }

    @Test
    void theSweepCanSeeTheMarkersWhenTheyAreThere() throws Exception {
        // The people who run the course do get them; without this the sweep above could pass vacuously.
        for (User runner : new User[] {owner, admin}) {
            boolean found = false;
            for (String pattern : getPatterns()) {
                String body = get(resolve(pattern), tokenFor(runner)).body();
                found |= body.contains(MARKERS[0]) && body.contains(MARKERS[1]) && body.contains(MARKERS[2]);
            }
            assertThat(found)
                    .as("a GET endpoint shows the hidden test to %s", runner.getEmail())
                    .isTrue();
        }
    }

    @Test
    void theSweepCoversTheEndpointsThatCarryTests() {
        assertThat(getPatterns())
                .contains(
                        "/assignments/{assignmentId}/tests",
                        "/assignments/{id}",
                        "/courses/{courseId}/assignments",
                        "/courses/{id}",
                        "/submissions/{submissionId}");
    }

    @Test
    void aHiddenTestIsOnlyMaskedNotRemovedForTheStudent() throws Exception {
        String body = get("/assignments/" + assignment.getId() + "/tests", tokenFor(student))
                .body();

        assertThat(body).contains("Hidden test 1").doesNotContain(MARKERS);
    }

    @Test
    void aHiddenResultIsOnlyMaskedNotRemovedForItsStudent() throws Exception {
        String body = get("/submissions/" + submission, tokenFor(student)).body();

        assertThat(body)
                .contains("Hidden test 1", "\"passed\":false")
                .doesNotContain(MARKERS)
                .doesNotContain(RESULT_MARKERS);
    }
}
