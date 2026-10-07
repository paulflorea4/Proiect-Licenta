package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 2.5c: `GET /admin/users` over a real HTTP connection. */
class RealHttpAdminUserListTests extends RealHttpTestBase {

    private String adminToken;

    @BeforeEach
    void startWithOneAdmin() {
        adminToken = tokenFor(saved("admin@example.com", Role.ADMIN));
    }

    @Test
    void listsIdEmailAndRoleAndNothingElse() throws Exception {
        saved("ada@example.com", Role.TEACHER);

        HttpResponse<String> response = get("/admin/users", adminToken);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].email"))
                .containsExactly("admin@example.com", "ada@example.com");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].role"))
                .containsExactly("ADMIN", "TEACHER");
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.items[0].*")).hasSize(3);
        assertThat(response.body())
                .doesNotContain("assword")
                .doesNotContain("hash")
                .doesNotContain("fullName");
    }

    @Test
    void theEnvelopeHasPageSizeAndTotals() throws Exception {
        saveStudents(24); // plus the admin: 25 users

        String body = get("/admin/users", adminToken).body();

        assertThat(JsonPath.<Integer>read(body, "$.page")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.size")).isEqualTo(20);
        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(25);
        assertThat(JsonPath.<Integer>read(body, "$.totalPages")).isEqualTo(2);
        assertThat(JsonPath.<List<Object>>read(body, "$.items")).hasSize(20);
    }

    @Test
    void pagesFollowEachOtherInIdOrderWithoutOverlapOrGaps() throws Exception {
        saveStudents(24);

        List<Integer> ids = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body =
                    get("/admin/users?page=" + page + "&size=10", adminToken).body();
            ids.addAll(JsonPath.<List<Integer>>read(body, "$.items[*].id"));
        }

        assertThat(ids).hasSize(25).doesNotHaveDuplicates().isSorted();
        assertThat(ids)
                .containsExactlyElementsOf(users.findAll().stream()
                        .map(u -> u.getId().intValue())
                        .sorted()
                        .toList());
    }

    @Test
    void aPagePastTheEndIsEmptyWithTheTotalsStillRight() throws Exception {
        saveStudents(4);

        HttpResponse<String> response = get("/admin/users?page=50&size=10", adminToken);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(response.body(), "$.items")).isEmpty();
        assertThat(JsonPath.<Integer>read(response.body(), "$.totalElements")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(response.body(), "$.page")).isEqualTo(50);
    }

    @Test
    void aPageBeyondWhatTheDatabaseLayerCanAddressIs400NotA500() throws Exception {
        assertThat(get("/admin/users?page=2147483647&size=100", adminToken).statusCode())
                .isEqualTo(400);
        assertThat(get("/admin/users?page=2147483648", adminToken).statusCode()).isEqualTo(400);
        // The last offset that still fits is an empty page, not an error.
        assertThat(get("/admin/users?page=2147483647&size=1", adminToken).statusCode())
                .isEqualTo(200);
    }

    @Test
    void anEmptyPageParameterMeansTheDefault() throws Exception {
        assertThat(JsonPath.<Integer>read(get("/admin/users?page=", adminToken).body(), "$.page"))
                .isZero();
    }

    @Test
    void anOversizedSizeIsClampedAndReportedAsSuch() throws Exception {
        saveStudents(3);

        String body = get("/admin/users?size=100000", adminToken).body();

        assertThat(JsonPath.<Integer>read(body, "$.size")).isEqualTo(100);
        assertThat(JsonPath.<List<Object>>read(body, "$.items")).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=-5", "page=abc", "size=abc", "page=1.5", "size=99999999999"})
    void aBadPagingParameterIs400(String query) throws Exception {
        assertThat(get("/admin/users?" + query, adminToken).statusCode()).isEqualTo(400);
    }

    @Test
    void aStudentAndATeacherAreRefusedAndAnonymousIs401() throws Exception {
        String student = tokenFor(saved("s@example.com", Role.STUDENT));
        String teacher = tokenFor(saved("t@example.com", Role.TEACHER));

        HttpResponse<String> asStudent = get("/admin/users", student);
        assertThat(asStudent.statusCode()).isEqualTo(403);
        assertThat(asStudent.body()).doesNotContain("admin@example.com");
        assertThat(get("/admin/users", teacher).statusCode()).isEqualTo(403);
        assertThat(get("/admin/users", null).statusCode()).isEqualTo(401);
        assertThat(get("/admin/users", "not.a.jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void aPromotedUserShowsTheirNewRoleInTheList() throws Exception {
        User student = saved("ada@example.com", Role.STUDENT);
        HttpRequest promote = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/admin/users/" + student.getId() + "/role"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"role\":\"TEACHER\"}"))
                .build();
        assertThat(http.send(promote, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);

        String body = get("/admin/users", adminToken).body();

        assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.email=='ada@example.com')].role"))
                .containsExactly("TEACHER");
    }

    // --- helpers ---------------------------------------------------------------------------

    private void saveStudents(int count) {
        for (int i = 0; i < count; i++) {
            saved("student" + i + "@example.com", Role.STUDENT);
        }
    }
}
