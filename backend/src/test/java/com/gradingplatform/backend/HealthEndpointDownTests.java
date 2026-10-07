package com.gradingplatform.backend;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gradingplatform.backend.controller.HealthController;
import com.gradingplatform.backend.security.JwtService;
import com.gradingplatform.backend.security.SecurityConfig;
import com.gradingplatform.backend.service.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 2.2b: `GET /health` when the database check fails: 503, and still only the status. */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthEndpointDownTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HealthService healthService;

    /** The security chain's JWT filter needs one; these requests carry no token, so it is never used. */
    @MockitoBean
    JwtService jwtService;

    @Test
    void returns503DownWhenTheDatabaseCheckFails() throws Exception {
        when(healthService.isDatabaseUp()).thenReturn(false);

        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", true));
    }

    @Test
    void returns200UpWhenTheDatabaseCheckSucceeds() throws Exception {
        when(healthService.isDatabaseUp()).thenReturn(true);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }
}
