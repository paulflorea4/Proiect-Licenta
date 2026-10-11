package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 4.1b: a language that exists only as a config entry is accepted by the assignment endpoints with
 * no code change, and one that is not configured is still refused.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "sandbox.languages.RUST.image=sandbox-rust:test",
            "sandbox.languages.RUST.source-file-name=main.rs",
            "sandbox.languages.RUST.compile-command=rustc,main.rs",
            "sandbox.languages.RUST.run-command=./main"
        })
class RealHttpNewLanguageTests extends RealHttpTestBase {

    private HttpResponse<String> createWithLanguage(String language) throws Exception {
        User teacher = saved("teacher@example.com", Role.TEACHER);
        Course course = courses.save(new Course("Algorithms", null, teacher.getId(), "ABCD2345"));
        String json = "{\"title\":\"T\",\"description\":\"D\",\"language\":\"" + language + "\",\"deadline\":\""
                + Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
                + "\",\"timeLimitMs\":2000,\"memoryLimitMb\":256}";
        return post("/courses/" + course.getId() + "/assignments", json, tokenFor(teacher));
    }

    @Test
    void aLanguageAddedOnlyInConfigCanBeUsedByAnAssignment() throws Exception {
        HttpResponse<String> response = createWithLanguage("rust");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.language")).isEqualTo("RUST");
    }

    @Test
    void theBuiltInLanguagesStillWorkNextToIt() throws Exception {
        assertThat(createWithLanguage("python").statusCode()).isEqualTo(201);
    }

    @Test
    void aLanguageThatIsNotConfiguredIsStillRefused() throws Exception {
        HttpResponse<String> response = createWithLanguage("COBOL");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("UNSUPPORTED_LANGUAGE");
    }
}
