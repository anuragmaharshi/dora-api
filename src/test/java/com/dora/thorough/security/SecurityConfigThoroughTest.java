package com.dora.thorough.security;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thorough tests for SecurityConfig.
 *
 * LLD-01 declares a fully permissive security chain:
 *   - CSRF disabled
 *   - anyRequest().permitAll()
 *
 * These tests pin the current (intentionally permissive) behaviour and will
 * surface regressions if someone partially tightens security before LLD-02
 * lands the full JWT implementation.
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JacksonConfig.class})
@DisplayName("SecurityConfig — thorough tests (LLD-01 permissive baseline)")
class SecurityConfigThoroughTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-5")
    @DisplayName("CSRF disabled — POST without CSRF token returns 405 (method not allowed), NOT 403 (forbidden)")
    void csrf_disabled_postReturns405NotForbidden() throws Exception {
        // CSRF protection would return 403; the endpoint simply doesn't support POST → 405.
        // If this returns 403 then CSRF is accidentally re-enabled.
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("An arbitrary unknown path returns 404, not 403 — permitAll() is in effect")
    void unknownPath_returns404NotForbidden() throws Exception {
        // If anyRequest().authenticated() were in effect this would be 401 or 403.
        // 404 proves the security layer is permitting the request through to the dispatcher.
        mockMvc.perform(get("/api/v1/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("AC-2")
    @DisplayName("Health endpoint accessible without any credentials — no authentication required")
    void healthEndpoint_noCredentials_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }
}
