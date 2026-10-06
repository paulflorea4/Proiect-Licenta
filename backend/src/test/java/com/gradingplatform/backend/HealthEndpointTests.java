package com.gradingplatform.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 2.2b: `GET /health` against the real application and a real (Testcontainers) database. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void returns200UpWhenTheDatabaseIsReachable() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void needsNoCredentials() throws Exception {
        // No Authorization header at all: the endpoint is public (2.4b must keep it that way).
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void exposesNothingBesidesTheStatus() throws Exception {
        mockMvc.perform(get("/health")).andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void otherEndpointsStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/some-protected-path")).andExpect(status().is4xxClientError());
    }

    @Test
    void onlyGetIsAllowedOnHealth() throws Exception {
        mockMvc.perform(post("/health")).andExpect(status().is4xxClientError());
    }
}
