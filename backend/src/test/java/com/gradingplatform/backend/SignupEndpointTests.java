package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** 2.3b: `POST /auth/signup` against the real application and a real (Testcontainers) database. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SignupEndpointTests {

    private static final String VALID_BODY =
            "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada Lovelace\"}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void createsTheUserAndReturns201WithoutAnySecret() throws Exception {
        signup(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void storesAStudentWithABcryptHashNotThePassword() throws Exception {
        signup(VALID_BODY).andExpect(status().isCreated());

        User saved = users.findByEmail("ada@example.com").orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.STUDENT);
        assertThat(saved.getPasswordHash()).isNotEqualTo("correct horse").startsWith("$2");
        assertThat(passwordEncoder.matches("correct horse", saved.getPasswordHash()))
                .isTrue();
        assertThat(passwordEncoder.matches("wrong horse", saved.getPasswordHash()))
                .isFalse();
    }

    @Test
    void theSamePasswordGetsADifferentHashEachTime() throws Exception {
        signup(VALID_BODY).andExpect(status().isCreated());
        signup(VALID_BODY.replace("ada@", "grace@")).andExpect(status().isCreated());

        String first = users.findByEmail("ada@example.com").orElseThrow().getPasswordHash();
        String second = users.findByEmail("grace@example.com").orElseThrow().getPasswordHash();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aRoleInTheBodyIsIgnoredAndTheUserIsStillAStudent() throws Exception {
        String body = "{\"email\":\"eve@example.com\",\"password\":\"correct horse\","
                + "\"fullName\":\"Eve\",\"role\":\"ADMIN\"}";

        signup(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("STUDENT"));

        assertThat(users.findByEmail("eve@example.com").orElseThrow().getRole()).isEqualTo(Role.STUDENT);
    }

    @Test
    void needsNoCredentialsAndNoCsrfToken() throws Exception {
        // signup() sends neither an Authorization header nor a CSRF token.
        signup(VALID_BODY).andExpect(status().isCreated());
    }

    @Test
    void aMissingFieldIsRejectedAndNothingIsSaved() throws Exception {
        signup("{\"email\":\"ada@example.com\",\"fullName\":\"Ada\"}").andExpect(status().isBadRequest());
        signup("{\"password\":\"correct horse\",\"fullName\":\"Ada\"}").andExpect(status().isBadRequest());
        signup("{\"email\":\"ada@example.com\",\"password\":\"correct horse\"}").andExpect(status().isBadRequest());

        assertThat(users.count()).isZero();
    }

    @Test
    void aBlankFieldIsRejectedAndNothingIsSaved() throws Exception {
        signup("{\"email\":\"  \",\"password\":\"correct horse\",\"fullName\":\"Ada\"}")
                .andExpect(status().isBadRequest());
        signup("{\"email\":\"ada@example.com\",\"password\":\"\",\"fullName\":\"Ada\"}")
                .andExpect(status().isBadRequest());
        signup("{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"\"}")
                .andExpect(status().isBadRequest());

        assertThat(users.count()).isZero();
    }

    @Test
    void aMalformedBodyIsRejected() throws Exception {
        signup("not json").andExpect(status().isBadRequest());
    }

    @Test
    void theRequestObjectNeverPrintsThePassword() {
        SignupRequest request = new SignupRequest("ada@example.com", "correct horse", "Ada");

        assertThat(request.toString()).doesNotContain("correct horse").contains("ada@example.com");
    }

    @Test
    void otherPostEndpointsStillRequireAuthentication() throws Exception {
        mockMvc.perform(post("/auth/something-else")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
        assertThat(users.count()).isZero();
    }

    private ResultActions signup(String json) throws Exception {
        return mockMvc.perform(
                post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
