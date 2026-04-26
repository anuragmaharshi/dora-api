package com.dora.smoke.audit;

import com.dora.config.JacksonConfig;
import com.dora.controllers.AuditController;
import com.dora.dto.AuditEntry;
import com.dora.security.JwtAuthFilter;
import com.dora.security.RoleNames;
import com.dora.security.SecurityConfig;
import com.dora.services.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-4 and AC-5 smoke tests for AuditController.
 *
 * <p>AC-4: COMPLIANCE_OFFICER calling GET /api/v1/audit gets 200 with rows.
 * <p>AC-5: PLATFORM_ADMIN calling the same endpoint gets 403 (BR-011, NFR-009).
 *
 * <p>Uses @WebMvcTest slice: AuditService is mocked, JwtAuthFilter is mocked to pass
 * through. Role-based security is asserted by @WithMockUser(roles = ...) rather than
 * issuing real JWTs — this is the cheapest test tier that covers the AC.
 */
@Tag("AC-4")
@DisplayName("AC-4/AC-5: AuditController role-based access control smoke tests")
@WebMvcTest(AuditController.class)
@Import({SecurityConfig.class, JacksonConfig.class})
class AuditControllerSmokeTest {

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @Autowired
    private MockMvc mockMvc;

    private UUID entityId;
    private AuditEntry stubEntry;

    @BeforeEach
    void setUp() throws Exception {
        entityId = UUID.randomUUID();

        stubEntry = new AuditEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ops@dora.local",
                "INCIDENT_CREATED",
                "INCIDENT",
                entityId,
                null,
                null,
                null,
                Instant.now()
        );

        when(auditService.findByEntity(eq("INCIDENT"), eq(entityId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stubEntry)));

        // JwtAuthFilter must pass through so requests reach the controller.
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
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: COMPLIANCE_OFFICER gets 200 with audit rows")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void complianceOfficer_gets200() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].action").value("INCIDENT_CREATED"));
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: OPS_ANALYST gets 200")
    @WithMockUser(roles = {RoleNames.OPS_ANALYST})
    void opsAnalyst_gets200() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: CISO gets 200")
    @WithMockUser(roles = {RoleNames.CISO})
    void ciso_gets200() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: PLATFORM_ADMIN gets 403 (BR-011, NFR-009)")
    @WithMockUser(roles = {RoleNames.PLATFORM_ADMIN})
    void platformAdmin_gets403() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: unauthenticated request gets 401")
    void unauthenticated_gets401() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isUnauthorized());
    }
}
