package com.dora;

import com.dora.config.JacksonConfig;
import com.dora.controllers.HealthController;
import com.dora.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JacksonConfig.class})
@Tag("AC-5")
@DisplayName("AC-5: GET /api/v1/health returns 200 with expected JSON shape")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_returns200WithExpectedShape() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("healthy"))
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.timestamp").exists());
    }
}
