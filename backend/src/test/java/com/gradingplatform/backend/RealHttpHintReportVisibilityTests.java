package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.ai.AiServiceClient;
import com.gradingplatform.backend.entity.AiFeedback;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.AiFeedbackRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 7.5d: who can report a hint, and who can see a report. Only the student who received a hint can
 * report it; a repeat keeps the first report; the people who run the course see it in their view of
 * the submission; and a report's reason is in no response anyone else can get. Like the hidden-test
 * sweep (3.6b), the last check walks <b>every</b> GET endpoint Spring maps, so an endpoint added later
 * is checked too. Two classmates each report a hint with a marker reason. The AI client is the same
 * mock as in {@link RealHttpHintRequestTests} (one shared test context); nothing here calls it.
 */
class RealHttpHintReportVisibilityTests extends RealHttpTestBase {

    private static final String AUTHOR_REASON = "AUTHOR-REASON-5c1d";
    private static final String CLASSMATE_REASON = "CLASSMATE-REASON-5c1d";

    @MockitoBean
    AiServiceClient ai;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private AiFeedbackRepository hints;

    private User owner;
    private User otherTeacher;
    private User admin;
    private User author;
    private User classmate;
    private User outsider;
    private Course course;
    private Assignment assignment;
    private long authorsSubmission;
    private long classmatesSubmission;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        author = saved("author@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        outsider = saved("outsider@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "SEEN2345"));
        enrollments.save(new Enrollment(course.getId(), author.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                "Read two integers and print their sum.",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
        authorsSubmission = gradedSubmissionOf(author);
        classmatesSubmission = gradedSubmissionOf(classmate);
        hints.save(new AiFeedback(authorsSubmission, 1, "Look at the last line."));
        hints.save(new AiFeedback(authorsSubmission, 2, "The operator in the print is the problem."));
        hints.save(new AiFeedback(classmatesSubmission, 1, "Read the input once."));
    }

    // ---- only your own hint ----

    @Test
    void onlyTheStudentWhoReceivedAHintCanReportIt() throws Exception {
        String before = reports();

        for (User other : List.of(classmate, outsider, owner, otherTeacher, admin)) {
            for (int level = 1; level <= 2; level++) {
                HttpResponse<String> response = report(other, authorsSubmission, level, AUTHOR_REASON);
                assertThat(response.statusCode())
                        .as("%s reporting the author's level %d", other.getEmail(), level)
                        .isEqualTo(403);
                assertThat(response.body()).doesNotContain("Look at the last line.");
            }
        }

        assertThat(reports()).as("no refused report changed anything").isEqualTo(before);
        assertThat(report(author, authorsSubmission, 1, AUTHOR_REASON).statusCode())
                .isEqualTo(200);
    }

    @Test
    void reportingYourOwnHintTouchesNoOneElsesRow() throws Exception {
        report(classmate, classmatesSubmission, 1, CLASSMATE_REASON);

        assertThat(reportedLevels(classmatesSubmission)).containsExactly(1);
        assertThat(reportedLevels(authorsSubmission)).isEmpty();
    }

    @Test
    void aLevelYourSubmissionDoesNotHaveIsNotFoundEvenWhenAClassmatesDoes() throws Exception {
        hints.save(new AiFeedback(classmatesSubmission, 3, "Three."));

        HttpResponse<String> response = report(author, authorsSubmission, 3, AUTHOR_REASON);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("HINT_NOT_FOUND");
        assertThat(reportedLevels(classmatesSubmission)).isEmpty();
    }

    // ---- idempotent ----

    @Test
    void aRepeatWithoutAReasonDoesNotEraseTheFirstReason() throws Exception {
        report(author, authorsSubmission, 1, AUTHOR_REASON);
        String first = reports();

        HttpResponse<String> noBody = post(path(authorsSubmission, 1), "", tokenFor(author));
        HttpResponse<String> blank = report(author, authorsSubmission, 1, "   ");

        for (HttpResponse<String> repeat : List.of(noBody, blank)) {
            assertThat(repeat.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(repeat.body(), "$.reportReason")).isEqualTo(AUTHOR_REASON);
        }
        assertThat(reports()).isEqualTo(first);
    }

    @Test
    void eachLevelIsReportedOnItsOwn() throws Exception {
        report(author, authorsSubmission, 2, "Second level is wrong.");

        assertThat(reportedLevels(authorsSubmission)).containsExactly(2);

        report(author, authorsSubmission, 1, "First level too.");

        assertThat(reportedLevels(authorsSubmission)).containsExactly(1, 2);
        assertThat(jdbc.queryForList(
                        "select report_reason from ai_feedback where submission_id = ? order by hint_level",
                        String.class,
                        authorsSubmission))
                .containsExactly("First level too.", "Second level is wrong.");
    }

    // ---- who sees a report ----

    @Test
    void thePeopleWhoRunTheCourseSeeEachReportOnItsOwnSubmission() throws Exception {
        report(author, authorsSubmission, 2, AUTHOR_REASON);
        report(classmate, classmatesSubmission, 1, CLASSMATE_REASON);

        for (User runner : List.of(owner, admin)) {
            String authors =
                    get("/submissions/" + authorsSubmission, tokenFor(runner)).body();
            String classmates = get("/submissions/" + classmatesSubmission, tokenFor(runner))
                    .body();

            assertThat(JsonPath.<List<Boolean>>read(authors, "$.hints[*].reported"))
                    .containsExactly(false, true);
            assertThat(JsonPath.<String>read(authors, "$.hints[1].reportReason"))
                    .isEqualTo(AUTHOR_REASON);
            assertThat(authors).doesNotContain(CLASSMATE_REASON);
            assertThat(JsonPath.<String>read(classmates, "$.hints[0].reportReason"))
                    .isEqualTo(CLASSMATE_REASON);
            assertThat(classmates).doesNotContain(AUTHOR_REASON);
        }
    }

    @Test
    void noGetEndpointShowsAReportToAnyoneButThePeopleWhoRunTheCourse() throws Exception {
        report(author, authorsSubmission, 1, AUTHOR_REASON);
        report(classmate, classmatesSubmission, 1, CLASSMATE_REASON);
        Map<String, User> callers = new LinkedHashMap<>();
        callers.put("the author", author);
        callers.put("a classmate", classmate);
        callers.put("a student who is not enrolled", outsider);
        callers.put("another teacher", otherTeacher);
        callers.put("nobody", null);

        for (long submission : List.of(authorsSubmission, classmatesSubmission)) {
            for (String pattern : getPatterns()) {
                String path = resolve(pattern, submission);
                for (var caller : callers.entrySet()) {
                    User user = caller.getValue();
                    HttpResponse<String> response = get(path, user == null ? null : tokenFor(user));
                    for (String reason : List.of(AUTHOR_REASON, CLASSMATE_REASON)) {
                        assertThat(response.body())
                                .as(
                                        "GET %s as %s (status %d) shows %s",
                                        path, caller.getKey(), response.statusCode(), reason)
                                .doesNotContain(reason)
                                .doesNotContain("\"reported\"");
                    }
                }
            }
        }
    }

    @Test
    void theSweepCanSeeTheReportsWhenTheyAreThere() throws Exception {
        report(author, authorsSubmission, 1, AUTHOR_REASON);
        report(classmate, classmatesSubmission, 1, CLASSMATE_REASON);

        // Without this the sweep above could pass vacuously.
        for (User runner : List.of(owner, admin)) {
            StringBuilder everything = new StringBuilder();
            for (long submission : List.of(authorsSubmission, classmatesSubmission)) {
                for (String pattern : getPatterns()) {
                    everything.append(
                            get(resolve(pattern, submission), tokenFor(runner)).body());
                }
            }
            assertThat(everything).as("what %s can read", runner.getEmail()).contains(AUTHOR_REASON, CLASSMATE_REASON);
        }
    }

    @Test
    void theSweepCoversTheEndpointsThatCarryHints() {
        assertThat(getPatterns()).contains("/submissions/{submissionId}", "/submissions/{submissionId}/hints");
    }

    // ---- helpers ----

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

    /** Fills a GET pattern with the ids of the world, the submission given; fails on a variable it does not know. */
    private String resolve(String pattern, long submission) {
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
                id = author.getId();
            } else {
                throw new AssertionError("teach the sweep about {id} in " + pattern);
            }
            path = path.replace("{id}", String.valueOf(id));
        }
        path = path.replace("{courseId}", String.valueOf(course.getId()))
                .replace("{assignmentId}", String.valueOf(assignment.getId()))
                .replace("{submissionId}", String.valueOf(submission));
        if (path.contains("{")) {
            throw new AssertionError("teach the sweep about the path variable in " + pattern);
        }
        return path;
    }

    private static String path(long submission, int level) {
        return "/submissions/" + submission + "/hints/" + level + "/report";
    }

    private HttpResponse<String> report(User as, long submission, int level, String reason) throws Exception {
        return post(path(submission, level), "{\"reason\":\"" + reason + "\"}", tokenFor(as));
    }

    /** Every report column of every hint, for before/after comparisons. */
    private String reports() {
        return jdbc.queryForList("select submission_id, hint_level, content, reported, report_reason, reported_at"
                        + " from ai_feedback order by submission_id, hint_level")
                .toString();
    }

    private List<Integer> reportedLevels(long submission) {
        return jdbc.queryForList(
                "select hint_level from ai_feedback where submission_id = ? and reported order by hint_level",
                Integer.class,
                submission);
    }

    private long gradedSubmissionOf(User student) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', 1, 'GRADED') returning id",
                Long.class,
                assignment.getId(),
                student.getId());
    }
}
