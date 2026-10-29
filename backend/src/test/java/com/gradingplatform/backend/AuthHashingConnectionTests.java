package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gradingplatform.backend.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 7.7h: BCrypt (about 100 ms) runs with no database transaction open and no pool connection held,
 * for signup as for signin (2.4a). The real encoder is spied on to see the moment it works. Its own
 * context (the spy), so it does not split the shared MockMvc context of the other signup tests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthHashingConnectionTests {

    private static final String BODY =
            "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada Lovelace\"}";

    @MockitoSpyBean
    PasswordEncoder encoder;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    DataSource dataSource;

    /** What was true each time the encoder worked: a transaction open, and the pool's active count. */
    private final List<String> whileHashing = new ArrayList<>();

    @BeforeEach
    void watchTheEncoder() throws Exception {
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        Answer<Object> recordThenHash = invocation -> {
            whileHashing.add("transaction=" + TransactionSynchronizationManager.isActualTransactionActive() + " active="
                    + pool.getActiveConnections());
            return invocation.callRealMethod();
        };
        doAnswer(recordThenHash).when(encoder).encode(any());
        doAnswer(recordThenHash).when(encoder).matches(any(), anyString());
    }

    @AfterEach
    void removeTheUsers() {
        users.deleteAll();
    }

    @Test
    void signupHashesWithNoTransactionOpenAndNoConnectionHeld() throws Exception {
        post("/auth/signup", BODY).andExpect(status().isCreated());

        assertThat(whileHashing).containsExactly("transaction=false active=0");
        assertThat(users.findByEmail("ada@example.com")).isPresent();
    }

    @Test
    void aSecondSignupForTheSameEmailIsStill409AndStoresNothingMore() throws Exception {
        // The duplicate is found by the insert's unique constraint, now outside any transaction of signup.
        post("/auth/signup", BODY).andExpect(status().isCreated());

        post("/auth/signup", BODY.replace("Ada Lovelace", "Someone Else")).andExpect(status().isConflict());

        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByEmail("ada@example.com").orElseThrow().getFullName())
                .isEqualTo("Ada Lovelace");
        assertThat(whileHashing).containsOnly("transaction=false active=0");
    }

    @Test
    void signinComparesWithNoTransactionOpenAndNoConnectionHeld() throws Exception {
        // 2.4a's existing rule, pinned the same way.
        post("/auth/signup", BODY).andExpect(status().isCreated());
        whileHashing.clear();

        post("/auth/signin", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\"}")
                .andExpect(status().isOk());

        assertThat(whileHashing).containsExactly("transaction=false active=0");
    }

    private ResultActions post(String path, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
