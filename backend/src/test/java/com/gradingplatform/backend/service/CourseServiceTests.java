package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.TestcontainersConfiguration;
import com.gradingplatform.backend.dto.CourseRequest;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import com.gradingplatform.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 3.1a: how `CourseService.create` copes with the database's constraints. Runs against the real
 * Postgres schema with a scripted code generator, because a collision of random codes cannot be
 * produced on demand and a mock repository would not prove that the constraint name we look for is
 * the one Postgres really reports.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CourseServiceTests {

    private static final CourseRequest REQUEST = new CourseRequest("Algorithms", "Sorting and graphs");

    @Autowired
    CourseRepository courses;

    @Autowired
    EnrollmentRepository enrollments;

    @Autowired
    UserRepository users;

    private final EnrollCodeGenerator generator = mock(EnrollCodeGenerator.class);

    private CourseService service;
    private User teacher;

    @BeforeEach
    void setUp() {
        cleanUp();
        service = new CourseService(courses, generator, new CourseAccess(courses, enrollments));
        teacher = users.save(new User("teacher@example.com", "hash", "Teacher", Role.TEACHER));
    }

    @AfterEach
    void cleanUp() {
        enrollments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    @Test
    void theCourseIsStoredWithTheOwnerTheCodeAndTheCreationTime() {
        when(generator.next()).thenReturn("ABCD2345");

        Course created = service.create(teacher.getId(), REQUEST);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCreatedAt()).isNotNull();
        Course stored = courses.findById(created.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Algorithms");
        assertThat(stored.getDescription()).isEqualTo("Sorting and graphs");
        assertThat(stored.getTeacherId()).isEqualTo(teacher.getId());
        assertThat(stored.getEnrollCode()).isEqualTo("ABCD2345");
    }

    @Test
    void aTakenCodeIsReplacedByTheNextOneAndTheOtherCourseIsUntouched() {
        courses.save(new Course("Existing", null, teacher.getId(), "TAKEN234"));
        when(generator.next()).thenReturn("TAKEN234", "FREE5678");

        Course created = service.create(teacher.getId(), REQUEST);

        assertThat(created.getEnrollCode()).isEqualTo("FREE5678");
        verify(generator, times(2)).next();
        assertThat(courses.findAll())
                .extracting(Course::getEnrollCode)
                .containsExactlyInAnyOrder("TAKEN234", "FREE5678");
    }

    @Test
    void severalCollisionsInARowAreRetriedUpToTheLimit() {
        for (int i = 1; i < CourseService.MAX_CODE_ATTEMPTS; i++) {
            courses.save(new Course("Existing " + i, null, teacher.getId(), "TAKEN23" + i));
        }
        // The first MAX_CODE_ATTEMPTS - 1 draws collide, the last one is free.
        when(generator.next()).thenReturn("TAKEN231", "TAKEN232", "TAKEN233", "TAKEN234", "LASTONE2");

        Course created = service.create(teacher.getId(), REQUEST);

        assertThat(created.getEnrollCode()).isEqualTo("LASTONE2");
        verify(generator, times(CourseService.MAX_CODE_ATTEMPTS)).next();
    }

    @Test
    void aGeneratorThatNeverProducesAFreeCodeStopsInsteadOfLooping() {
        courses.save(new Course("Existing", null, teacher.getId(), "TAKEN234"));
        when(generator.next()).thenReturn("TAKEN234");

        assertThatThrownBy(() -> service.create(teacher.getId(), REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No free enrollment code");

        verify(generator, times(CourseService.MAX_CODE_ATTEMPTS)).next();
        assertThat(courses.count()).isEqualTo(1); // only the one that was already there
    }

    @Test
    void aTeacherWhoseAccountWasDeletedIsRefusedAsSuch() {
        when(generator.next()).thenReturn("ABCD2345");
        long goneTeacher = teacher.getId() + 1_000_000;

        assertThatThrownBy(() -> service.create(goneTeacher, REQUEST))
                .isInstanceOf(AccountNoLongerExistsException.class);

        assertThat(courses.count()).isZero();
        // Not retried: a missing teacher is not a code collision.
        verify(generator, times(1)).next();
    }

    @Test
    void aViolationOfAnotherConstraintIsNotMistakenForACollision() {
        when(generator.next()).thenReturn("ABCD2345");
        // A title longer than VARCHAR(255) can only get past validation if a caller skips it; the
        // database then refuses with a different error, which must surface as it is.
        CourseRequest tooLong = new CourseRequest("x".repeat(300), null);

        assertThatThrownBy(() -> service.create(teacher.getId(), tooLong))
                .isNotInstanceOf(AccountNoLongerExistsException.class)
                .isNotInstanceOf(IllegalStateException.class);

        verify(generator, times(1)).next();
    }

    @Test
    void theConstraintNamesWeLookForAreTheOnesPostgresReports() {
        courses.save(new Course("Existing", null, teacher.getId(), "TAKEN234"));

        // Provoke each violation directly and read the name Hibernate reports.
        assertThatThrownBy(() -> courses.saveAndFlush(new Course("Dup", null, teacher.getId(), "TAKEN234")))
                .matches(e -> Constraints.isViolationOf(e, CourseService.ENROLL_CODE_UNIQUE_CONSTRAINT));
        assertThatThrownBy(
                        () -> courses.saveAndFlush(new Course("Orphan", null, teacher.getId() + 1_000_000, "OTHER234")))
                .matches(e -> Constraints.isViolationOf(e, CourseService.TEACHER_FOREIGN_KEY));
    }
}
