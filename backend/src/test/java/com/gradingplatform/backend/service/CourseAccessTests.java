package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.TestcontainersConfiguration;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import com.gradingplatform.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * 3.1c: the visibility rule in one place. {@code CourseAccess.canView} (one course) and
 * {@code CourseService.listVisibleTo} (a query) state the same rule twice; the last test compares
 * them for every user and course so they cannot drift apart.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CourseAccessTests {

    @Autowired
    CourseAccess access;

    @Autowired
    CourseService courseService;

    @Autowired
    CourseRepository courses;

    @Autowired
    EnrollmentRepository enrollments;

    @Autowired
    UserRepository users;

    private User owner;
    private User otherTeacher;
    private User enrolled;
    private User outsider;
    private User admin;
    private Course mine;
    private Course theirs;

    @BeforeEach
    void world() {
        cleanUp();
        owner = users.save(new User("owner@example.com", "hash", "Owner", Role.TEACHER));
        otherTeacher = users.save(new User("other@example.com", "hash", "Other", Role.TEACHER));
        enrolled = users.save(new User("enrolled@example.com", "hash", "Enrolled", Role.STUDENT));
        outsider = users.save(new User("outsider@example.com", "hash", "Outsider", Role.STUDENT));
        admin = users.save(new User("root@example.com", "hash", "Root", Role.ADMIN));
        mine = courses.save(new Course("Mine", null, owner.getId(), "CODE0001"));
        theirs = courses.save(new Course("Theirs", null, otherTeacher.getId(), "CODE0002"));
        enrollments.save(new Enrollment(mine.getId(), enrolled.getId()));
        enrollments.save(new Enrollment(theirs.getId(), enrolled.getId()));
    }

    @AfterEach
    void cleanUp() {
        enrollments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    @Test
    void theRuleByRoleAndRelationship() {
        assertThat(access.canView(owner.getId(), Role.TEACHER, mine)).isTrue();
        assertThat(access.canView(otherTeacher.getId(), Role.TEACHER, mine)).isFalse();
        assertThat(access.canView(enrolled.getId(), Role.STUDENT, mine)).isTrue();
        assertThat(access.canView(outsider.getId(), Role.STUDENT, mine)).isFalse();
        assertThat(access.canView(admin.getId(), Role.ADMIN, mine)).isTrue();
    }

    @Test
    void requireViewableReturnsTheCourseOrThrowsTheSameErrorForAbsentAndHidden() {
        assertThat(access.requireViewable(owner.getId(), Role.TEACHER, mine.getId())
                        .getId())
                .isEqualTo(mine.getId());

        assertThatThrownBy(() -> access.requireViewable(outsider.getId(), Role.STUDENT, mine.getId()))
                .isInstanceOf(CourseNotFoundException.class);
        assertThatThrownBy(() -> access.requireViewable(outsider.getId(), Role.STUDENT, 987_654_321L))
                .isInstanceOf(CourseNotFoundException.class);
    }

    @Test
    void theRoleIsWhatDecidesNotTheRelationshipAlone() {
        // The owner, presented as a student (the token says so), is not enrolled and sees nothing.
        assertThat(access.canView(owner.getId(), Role.STUDENT, mine)).isFalse();
        // An enrolled student, presented as a teacher, owns nothing.
        assertThat(access.canView(enrolled.getId(), Role.TEACHER, mine)).isFalse();
    }

    @Test
    void theListAndTheSingleCourseCheckAgreeForEveryUserAndCourse() {
        List<Course> all = courses.findAll();
        List<String> disagreements = new ArrayList<>();

        for (User user : users.findAll()) {
            List<Long> listed = courseService
                    .listVisibleTo(user.getId(), user.getRole(), PageRequest.of(0, 100, Sort.by("id")))
                    .map(Course::getId)
                    .getContent();
            for (Course course : all) {
                boolean inList = listed.contains(course.getId());
                boolean viewable = access.canView(user.getId(), user.getRole(), course);
                if (inList != viewable) {
                    disagreements.add(
                            user.getEmail() + " / " + course.getTitle() + ": list=" + inList + " canView=" + viewable);
                }
            }
        }

        assertThat(disagreements).isEmpty();
    }
}
