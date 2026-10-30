package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.gradingplatform.backend.TestcontainersConfiguration;
import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.AssignmentRepository;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import com.gradingplatform.backend.repository.UserRepository;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import com.gradingplatform.backend.sandbox.TestLanguages;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 3.3a: the parts of `AssignmentService.create` that a request over HTTP cannot pin down: the exact
 * boundary of "in the future" (a fixed clock) and the foreign-key name we look for when the course
 * disappears mid-request (the real Postgres schema reports it).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AssignmentServiceTests {

    private static final Instant NOW = Instant.parse("2030-06-01T12:00:00Z");

    @Autowired
    AssignmentRepository assignments;

    @Autowired
    CourseRepository courses;

    @Autowired
    EnrollmentRepository enrollments;

    @Autowired
    UserRepository users;

    private final SandboxProperties properties = TestLanguages.javaAndPython();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private User teacher;
    private Course course;
    private AssignmentService service;

    @BeforeEach
    void setUp() {
        cleanUp();
        teacher = users.save(new User("teacher@example.com", "hash", "Teacher", Role.TEACHER));
        course = courses.save(new Course("Algorithms", null, teacher.getId(), "ABCD2345"));
        service = new AssignmentService(
                assignments, new CourseAccess(courses, enrollments), properties, mock(PublishRules.class), clock);
    }

    @AfterEach
    void cleanUp() {
        assignments.deleteAll();
        enrollments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    private static AssignmentRequest request(Instant deadline) {
        return new AssignmentRequest("Sorting", "Sort it", "JAVA", deadline, null, 2000, 256, null);
    }

    @Test
    void aDeadlineExactlyNowIsNotInTheFuture() {
        assertThatThrownBy(() -> service.create(teacher.getId(), Role.TEACHER, course.getId(), request(NOW)))
                .isInstanceOf(DeadlineNotInFutureException.class);
        assertThat(assignments.count()).isZero();
    }

    @Test
    void aDeadlineOneMillisecondAfterNowIsAccepted() {
        Assignment created = service.create(teacher.getId(), Role.TEACHER, course.getId(), request(NOW.plusMillis(1)));

        assertThat(created.getId()).isNotNull();
        assertThat(created.isPublished()).isFalse();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
    }

    @Test
    void theLatestDeadlineIsFiveYearsAhead() {
        // 8.0e, the human's value.
        assertThat(AssignmentRequest.DEADLINE_MAX_AHEAD).isEqualTo(Period.ofYears(5));
    }

    @Test
    void aDeadlineExactlyFiveYearsAheadIsAccepted() {
        Instant latest = Instant.parse("2035-06-01T12:00:00Z");

        Assignment created = service.create(teacher.getId(), Role.TEACHER, course.getId(), request(latest));

        assertThat(created.getDeadline()).isEqualTo(latest);
    }

    @Test
    void aDeadlineOneMillisecondPastFiveYearsAheadIsRefused() {
        Instant tooFar = Instant.parse("2035-06-01T12:00:00Z").plusMillis(1);

        assertThatThrownBy(() -> service.create(teacher.getId(), Role.TEACHER, course.getId(), request(tooFar)))
                .isInstanceOf(DeadlineTooFarException.class);
        assertThat(assignments.count()).isZero();
    }

    @Test
    void aDeadlineOneMillisecondBeforeNowIsRefused() {
        assertThatThrownBy(() ->
                        service.create(teacher.getId(), Role.TEACHER, course.getId(), request(NOW.minusMillis(1))))
                .isInstanceOf(DeadlineNotInFutureException.class);
    }

    @Test
    void aCourseDeletedAfterTheAccessCheckIsReportedAsNotFound() {
        // The access check passes (a stand-in that always allows), then the insert meets a course
        // that no longer exists: Postgres names assignments_course_id_fkey, which we map to a 404.
        CourseAccess allowAll = mock(CourseAccess.class);
        AssignmentService racing =
                new AssignmentService(assignments, allowAll, properties, mock(PublishRules.class), clock);
        long goneCourseId = course.getId() + 1_000_000;

        assertThatThrownBy(
                        () -> racing.create(teacher.getId(), Role.TEACHER, goneCourseId, request(NOW.plusSeconds(60))))
                .isInstanceOf(CourseNotFoundException.class);
        assertThat(assignments.count()).isZero();
    }
}
