package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gradingplatform.backend.dto.SigninRequest;
import com.gradingplatform.backend.dto.SigninResponse;
import com.gradingplatform.backend.dto.UserResponse;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** 2.4a: `POST /auth/signin` against the real application and a real (Testcontainers) database. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SigninEndpointTests {

    private static final String PASSWORD = "correct horse";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Value("${jwt.secret}")
    String jwtSecret;

    // --- success ---------------------------------------------------------------------------

    @Test
    void returns200WithATokenTheUserAndTheExpiry() throws Exception {
        signUp("ada@example.com", "Ada Lovelace");

        signIn("ada@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(24 * 60 * 60))
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.user.fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.id").isNumber());
    }

    @Test
    void theTokenCarriesTheUsersIdEmailAndRoleAndIsSignedWithOurSecret() throws Exception {
        signUp("ada@example.com", "Ada");
        User ada = users.findByEmail("ada@example.com").orElseThrow();

        Claims claims = claimsOf(signInBody("ada@example.com", PASSWORD).token());

        assertThat(claims.getSubject()).isEqualTo(String.valueOf(ada.getId()));
        assertThat(claims.get("email", String.class)).isEqualTo("ada@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("STUDENT");
    }

    @Test
    void theTokenIsValidForAboutTwentyFourHoursFromNow() throws Exception {
        signUp("ada@example.com", "Ada");
        Instant before = Instant.now();

        Claims claims = claimsOf(signInBody("ada@example.com", PASSWORD).token());

        Duration lifetime = Duration.between(
                claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
        assertThat(lifetime).isEqualTo(Duration.ofHours(24));
        assertThat(claims.getIssuedAt().toInstant())
                .isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void aTeacherGetsATokenWithTheTeacherRole() throws Exception {
        users.saveAndFlush(
                new User("teacher@example.com", passwordEncoder.encode(PASSWORD), "Tom Teacher", Role.TEACHER));

        SigninResponse body = signInBody("teacher@example.com", PASSWORD);

        assertThat(claimsOf(body.token()).get("role", String.class)).isEqualTo("TEACHER");
        assertThat(body.user().role()).isEqualTo(Role.TEACHER);
    }

    @Test
    void theEmailIsMatchedIgnoringCaseAndSurroundingSpaces() throws Exception {
        signUp("Ada@Example.com", "Ada");

        signIn("  ADA@example.COM ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void needsNoCredentialsAndNoCsrfToken() throws Exception {
        signUp("ada@example.com", "Ada");

        // signIn() sends neither an Authorization header nor a CSRF token.
        signIn("ada@example.com", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theResponseNeverContainsThePasswordOrItsHash() throws Exception {
        signUp("ada@example.com", "Ada");

        String body = signIn("ada@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("$2a$").doesNotContain("passwordHash");
    }

    @Test
    void theTokenIsInTheBodyOnlyNotInACookieOrHeader() throws Exception {
        signUp("ada@example.com", "Ada");

        var response = signIn("ada@example.com", PASSWORD).andReturn().getResponse();

        assertThat(response.getCookies()).isEmpty();
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(response.getHeader("Authorization")).isNull();
    }

    // --- failure ---------------------------------------------------------------------------

    @Test
    void aWrongPasswordIsUnauthorized() throws Exception {
        signUp("ada@example.com", "Ada");

        signIn("ada@example.com", "wrong password").andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownEmailIsUnauthorized() throws Exception {
        signIn("nobody@example.com", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownEmailAndAWrongPasswordLookExactlyTheSame() throws Exception {
        signUp("ada@example.com", "Ada");

        var wrongPassword =
                signIn("ada@example.com", "wrong password").andReturn().getResponse();
        var unknownEmail =
                signIn("nobody@example.com", "wrong password").andReturn().getResponse();

        assertThat(unknownEmail.getStatus()).isEqualTo(wrongPassword.getStatus());
        assertThat(unknownEmail.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
        assertThat(unknownEmail.getContentType()).isEqualTo(wrongPassword.getContentType());
    }

    @Test
    void aFailedSigninIssuesNoToken() throws Exception {
        signUp("ada@example.com", "Ada");

        String body = signIn("ada@example.com", "wrong password")
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("token").doesNotContain("eyJ");
    }

    @Test
    void missingAndBlankFieldsAreBadRequests() throws Exception {
        rawSignIn("{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isBadRequest());
        rawSignIn("{\"email\":\"ada@example.com\"}").andExpect(status().isBadRequest());
        signIn("  ", PASSWORD).andExpect(status().isBadRequest());
        signIn("ada@example.com", "").andExpect(status().isBadRequest());
        rawSignIn("not json").andExpect(status().isBadRequest());
    }

    @Test
    void aPasswordOverTheBcryptLimitIsABadRequestNotAServerError() throws Exception {
        signIn("ada@example.com", "a".repeat(73)).andExpect(status().isBadRequest());
    }

    @Test
    void signinOnlyAcceptsPost() throws Exception {
        mockMvc.perform(get("/auth/signin")).andExpect(status().is4xxClientError());
    }

    // --- logging safety --------------------------------------------------------------------

    @Test
    void theRequestAndResponseObjectsNeverPrintTheirSecrets() {
        SigninRequest request = new SigninRequest("ada@example.com", PASSWORD);
        SigninResponse response = new SigninResponse(
                "eyJ.secret.token", "Bearer", 86400, new UserResponse(1L, "ada@example.com", "Ada", Role.STUDENT));

        assertThat(request.toString()).doesNotContain(PASSWORD).contains("ada@example.com");
        assertThat(response.toString()).doesNotContain("eyJ.secret.token").contains("ada@example.com");
    }

    // --- helpers ---------------------------------------------------------------------------

    private Claims claimsOf(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private void signUp(String email, String fullName) throws Exception {
        String json =
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"" + fullName + "\"}";
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    private ResultActions signIn(String email, String password) throws Exception {
        return rawSignIn("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private ResultActions rawSignIn(String json) throws Exception {
        return mockMvc.perform(
                post("/auth/signin").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private SigninResponse signInBody(String email, String password) throws Exception {
        String json = signIn(email, password)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new tools.jackson.databind.json.JsonMapper().readValue(json, SigninResponse.class);
    }
}
