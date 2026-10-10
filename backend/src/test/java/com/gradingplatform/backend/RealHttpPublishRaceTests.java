package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 3.6b: the publish rule (rubric weights add up to 100, a test in every `TESTS` criterion) as a
 * whole: the round trips a teacher actually makes, and the claim that it cannot be raced by a
 * rubric edit. The rule's single cases are in {@link RealHttpPublishRulesTests}.
 */
class RealHttpPublishRaceTests extends RealHttpTestBase {

    @Autowired
    DataSource dataSource;

    private User owner;
    private Assignment assignment;
    private RubricCriterion first;
    private RubricCriterion second;
    private TestCase secondTest;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "d",
                "JAVA",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        first = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Small", CriterionType.TESTS, 40));
        second = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Large", CriterionType.TESTS, 60));
        testCases.save(new TestCase(assignment.getId(), first.getId(), "a", "1", "1", TestVisibility.PUBLIC, 1, 1));
        secondTest = testCases.save(
                new TestCase(assignment.getId(), second.getId(), "b", "2", "2", TestVisibility.HIDDEN, 1, 2));
    }

    private String url(String suffix) {
        return "/assignments/" + assignment.getId() + suffix;
    }

    private HttpResponse<String> publish() throws Exception {
        return send("POST", url("/publish"), tokenFor(owner), null);
    }

    private HttpResponse<String> unpublish() throws Exception {
        return send("POST", url("/unpublish"), tokenFor(owner), null);
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private boolean published() {
        return assignments.findById(assignment.getId()).orElseThrow().isPublished();
    }

    // --- the round trips ---------------------------------------------------------------------

    @Test
    void aValidRubricPublishesAndABrokenOneIsRefusedAgainAfterAnUnpublishAndAnEdit() throws Exception {
        assertThat(publish().statusCode()).isEqualTo(200);
        assertThat(published()).isTrue();

        // Option A (3.5a): to edit, unpublish first. Break the weights, and the next publish is refused.
        assertThat(unpublish().statusCode()).isEqualTo(200);
        HttpResponse<String> edit = send(
                "PUT",
                url("/rubric/" + second.getId()),
                tokenFor(owner),
                "{\"name\":\"Large\",\"type\":\"TESTS\",\"weight\":59}");
        assertThat(edit.statusCode()).isEqualTo(200);

        HttpResponse<String> refused = publish();
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("RUBRIC_WEIGHTS_INVALID");
        assertThat(published()).isFalse();

        // Fix it through the API and it goes through again.
        send(
                "PUT",
                url("/rubric/" + second.getId()),
                tokenFor(owner),
                "{\"name\":\"Large\",\"type\":\"TESTS\",\"weight\":60}");
        assertThat(publish().statusCode()).isEqualTo(200);
        assertThat(published()).isTrue();
    }

    @Test
    void deletingTheLastTestOfACriterionBlocksTheNextPublish() throws Exception {
        assertThat(send("DELETE", url("/tests/" + secondTest.getId()), tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(204);

        HttpResponse<String> refused = publish();

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("CRITERION_HAS_NO_TESTS");
        assertThat(published()).isFalse();
    }

    @Test
    void deletingACriterionAndItsTestsLeavesTheWeightsShort() throws Exception {
        send("DELETE", url("/tests/" + secondTest.getId()), tokenFor(owner), null);
        assertThat(send("DELETE", url("/rubric/" + second.getId()), tokenFor(owner), null)
                        .statusCode())
                .isEqualTo(204);

        HttpResponse<String> refused = publish();

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("RUBRIC_WEIGHTS_INVALID");
    }

    @Test
    void manyCriteriaThatAddUpTo100Publish() throws Exception {
        testCases.deleteAll();
        rubricCriteria.deleteAll();
        for (int i = 0; i < 100; i++) {
            RubricCriterion c =
                    rubricCriteria.save(new RubricCriterion(assignment.getId(), "c" + i, CriterionType.TESTS, 1));
            testCases.save(
                    new TestCase(assignment.getId(), c.getId(), "t" + i, "i", "o", TestVisibility.PUBLIC, 1, i + 1));
        }

        assertThat(publish().statusCode()).isEqualTo(200);
    }

    // --- the race ----------------------------------------------------------------------------

    @Test
    void aPublishWaitsForARubricEditInFlightAndThenSeesIt() throws Exception {
        // Another request is part-way through changing the rubric: it holds the assignment's row
        // lock (as every rubric write does) and has lowered a weight, uncommitted.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from assignments where id = " + assignment.getId() + " for update");
                statement.executeUpdate("update rubric_criteria set weight = 30 where id = " + second.getId());

                CompletableFuture<HttpResponse<String>> publishing = CompletableFuture.supplyAsync(() -> {
                    try {
                        return publish();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(500);
                assertThat(publishing)
                        .as("publish must wait for the edit, not read the old rubric")
                        .isNotDone();

                connection.commit();
                HttpResponse<String> response = publishing.get(10, TimeUnit.SECONDS);

                assertThat(response.statusCode()).isEqualTo(409);
                assertThat(code(response)).isEqualTo("RUBRIC_WEIGHTS_INVALID");
            }
        }
        assertThat(published()).isFalse();
    }

    @Test
    void aPublishThatWinsTheLockMakesALaterRubricEditWaitAndThenBeRefused() throws Exception {
        // The reverse order: publish holds the lock; an edit that arrives meanwhile must see a
        // published assignment, not slip a change into it.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from assignments where id = " + assignment.getId() + " for update");

                CompletableFuture<HttpResponse<String>> editing = CompletableFuture.supplyAsync(() -> {
                    try {
                        return send(
                                "PUT",
                                url("/rubric/" + second.getId()),
                                tokenFor(owner),
                                "{\"name\":\"Large\",\"type\":\"TESTS\",\"weight\":10}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(500);
                assertThat(editing).isNotDone();

                statement.executeUpdate("update assignments set published = true where id = " + assignment.getId());
                connection.commit();
                HttpResponse<String> response = editing.get(10, TimeUnit.SECONDS);

                assertThat(response.statusCode()).isEqualTo(409);
                assertThat(code(response)).isEqualTo("ASSIGNMENT_PUBLISHED");
            }
        }
        assertThat(rubricCriteria.findById(second.getId()).orElseThrow().getWeight())
                .isEqualTo(60);
    }
}
