package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 7.1c: an assignment's memory limit may not be below its language's `min-memory-mb` (128 MB for
 * Java in `application.yml`, none for Python). Checked on create, and on update only when the
 * language or the memory limit changes, so an assignment made before the minimum is not frozen.
 */
class RealHttpLanguageMemoryMinimumTests extends RealHttpTestBase {

    private static final String CODE = "MEMORY_LIMIT_BELOW_LANGUAGE_MINIMUM";

    private User teacher;
    private Course course;

    @BeforeEach
    void world() {
        teacher = saved("teacher@example.com", Role.TEACHER);
        course = courses.save(new Course("Algorithms", null, teacher.getId(), "MEMO2345"));
    }

    @AfterEach
    void removeWhatReferencesAssignments() {
        jdbc.update("delete from submissions");
    }

    // ---- create ----

    @ParameterizedTest
    @ValueSource(ints = {1, 64, 127})
    void aJavaAssignmentBelow128MbIsRefused(int memory) throws Exception {
        HttpResponse<String> response = create(body("JAVA", memory));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(CODE);
        assertThat(response.body())
                .doesNotContain(String.valueOf(memory) + "MB")
                .doesNotContain("128");
        assertThat(assignments.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 256, 1024})
    void aJavaAssignmentAtOrAbove128MbIsCreated(int memory) throws Exception {
        HttpResponse<String> response = create(body("JAVA", memory));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(response.body(), "$.memoryLimitMb")).isEqualTo(memory);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16, 64})
    void aLanguageWithoutAMinimumTakesAnyValidLimit(int memory) throws Exception {
        HttpResponse<String> response = create(body("PYTHON", memory));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    @Test
    void theLanguageIsMatchedAsTypedBeforeTheMinimumApplies() throws Exception {
        // The language is trimmed and upper-cased (3.3a), and the minimum is that language's.
        assertThat(create(body(" java ", 64)).statusCode()).isEqualTo(400);
        assertThat(create(body("python", 64)).statusCode()).isEqualTo(201);
    }

    // ---- update ----

    @Test
    void anAssignmentBelowTheMinimumKeepsWorkingWhileNeitherLanguageNorMemoryChanges() throws Exception {
        Assignment old = stored("JAVA", 64); // made before the minimum existed

        HttpResponse<String> response = update(old, body("JAVA", 64, "title", "\"Renamed\""));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Renamed");
    }

    @Test
    void changingTheMemoryOfAJavaAssignmentToBelowTheMinimumIsRefusedAndChangesNothing() throws Exception {
        Assignment existing = stored("JAVA", 256);

        HttpResponse<String> response = update(existing, body("JAVA", 100, "title", "\"Renamed\""));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(CODE);
        Assignment after = assignments.findById(existing.getId()).orElseThrow();
        assertThat(after.getMemoryLimitMb()).isEqualTo(256);
        assertThat(after.getTitle()).isEqualTo("Sorting");
    }

    @Test
    void raisingAnOldAssignmentToTheMinimumIsAllowedButNotToAnotherValueBelowIt() throws Exception {
        Assignment old = stored("JAVA", 64);

        assertThat(update(old, body("JAVA", 100)).statusCode()).isEqualTo(400);
        assertThat(update(old, body("JAVA", 128)).statusCode()).isEqualTo(200);
        assertThat(assignments.findById(old.getId()).orElseThrow().getMemoryLimitMb())
                .isEqualTo(128);
    }

    @Test
    void switchingToALanguageWhoseMinimumTheLimitDoesNotMeetIsRefused() throws Exception {
        Assignment python = stored("PYTHON", 64);

        HttpResponse<String> response = update(python, body("JAVA", 64));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(CODE);
        assertThat(assignments.findById(python.getId()).orElseThrow().getLanguage())
                .isEqualTo("PYTHON");
    }

    @Test
    void switchingAwayFromJavaFreesTheLimit() throws Exception {
        Assignment java = stored("JAVA", 256);

        assertThat(update(java, body("PYTHON", 32)).statusCode()).isEqualTo(200);
    }

    // ---- helpers ----

    private Assignment stored(String language, int memory) {
        // Saved directly, as an assignment made before 7.1c (or by seed SQL) would be.
        return assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "Sort the numbers",
                language,
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                memory,
                null));
    }

    private HttpResponse<String> create(String json) throws Exception {
        return send("POST", "/courses/" + course.getId() + "/assignments", tokenFor(teacher), json);
    }

    private HttpResponse<String> update(Assignment assignment, String json) throws Exception {
        return send("PUT", "/assignments/" + assignment.getId(), tokenFor(teacher), json);
    }

    /** A valid assignment body with this language and memory limit; the deadline is the stored one's day. */
    private static String body(String language, int memory, String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "\"Sorting\"");
        fields.put("description", "\"Sort the numbers\"");
        fields.put("language", "\"" + language + "\"");
        fields.put("deadline", "\"" + Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS) + "\"");
        fields.put("timeLimitMs", "2000");
        fields.put("memoryLimitMb", String.valueOf(memory));
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        StringBuilder json = new StringBuilder("{");
        fields.forEach((key, value) -> json.append(json.length() > 1 ? "," : "")
                .append('"')
                .append(key)
                .append("\":")
                .append(value));
        return json.append('}').toString();
    }
}
