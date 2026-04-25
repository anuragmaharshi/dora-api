package com.dora;

import com.dora.config.JacksonConfig;
import com.dora.controllers.HealthController;
import com.dora.security.JwtAuthFilter;
import com.dora.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for HealthController.
 *
 * SecurityConfig is imported so the permit-all rule for /api/v1/health is active.
 * JwtAuthFilter is mocked because @WebMvcTest doesn't load the full application
 * context; JPA repositories and DevJwtService are not in scope here.
 * The mock is configured to pass through the filter chain so the request reaches
 * the controller (a no-op mock would swallow the request without forwarding).
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JacksonConfig.class})
@Tag("AC-5")
@DisplayName("AC-5: GET /api/v1/health returns 200 with expected JSON shape")
class HealthControllerTest {

    // Mocked so SecurityConfig can inject it without requiring the full JPA context.
    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_returns200WithExpectedShape() throws Exception {
        // Configure the filter mock to actually pass through the chain.
        // Without this, the mock intercepts and drops the request before it reaches the controller.
        Mockito.doAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            HttpServletResponse res = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(
                Mockito.any(HttpServletRequest.class),
                Mockito.any(HttpServletResponse.class),
                Mockito.any(FilterChain.class));

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("healthy"))
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.timestamp").exists());
    }
}
