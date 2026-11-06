package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.sandbox.LanguageSpec;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 9.2b: `GET /languages`, the configured languages for the teacher's assignment form. */
class RealHttpLanguagesTests extends RealHttpTestBase {

    @Autowired
    SandboxProperties properties;

    @Test
    void aTeacherGetsEveryConfiguredLanguageInConfigOrderWithItsMemoryMinimum() throws Exception {
        HttpResponse<String> response = get("/languages", tokenFor(saved("tom@example.com", Role.TEACHER)));

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> ids = JsonPath.read(response.body(), "$.languages[*].id");
        assertThat(ids).isEqualTo(properties.ids());
        // application.yml: Java declares 128 MB, Python nothing, so the sandbox's floor (7.7f).
        assertThat(JsonPath.<List<Integer>>read(response.body(), "$.languages[?(@.id == 'JAVA')].minMemoryMb"))
                .containsExactly(128);
        assertThat(JsonPath.<List<Integer>>read(response.body(), "$.languages[?(@.id == 'PYTHON')].minMemoryMb"))
                .containsExactly(LanguageSpec.SANDBOX_MIN_MEMORY_MB);
    }

    @Test
    void eachLanguageShowsOnlyItsIdAndMinimum() throws Exception {
        HttpResponse<String> response = get("/languages", tokenFor(saved("tom@example.com", Role.TEACHER)));

        List<Map<String, Object>> languages = JsonPath.read(response.body(), "$.languages");
        assertThat(languages)
                .allSatisfy(language -> assertThat(language.keySet()).containsExactlyInAnyOrder("id", "minMemoryMb"));
        assertThat(response.body()).doesNotContain("image").doesNotContain("Command");
    }

    @Test
    void anAdminMayAsk() throws Exception {
        assertThat(get("/languages", tokenFor(saved("root@example.com", Role.ADMIN)))
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void aStudentIsRefusedAndAnonymousIsUnauthenticated() throws Exception {
        assertThat(get("/languages", tokenFor(saved("sam@example.com", Role.STUDENT)))
                        .statusCode())
                .isEqualTo(403);
        assertThat(get("/languages", null).statusCode()).isEqualTo(401);
    }
}
