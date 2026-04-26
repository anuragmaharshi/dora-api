package com.dora.thorough.audit;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thorough @WebMvcTest tests for {@link AuditController}.
 *
 * <p>Covers: all five permitted roles (parameterized), forbidden PLATFORM_ADMIN (AC-5/BR-011),
 * unauthenticated 401, missing parameters (400), response shape, and default pagination behaviour.
 */
@Tag("AC-4")
@DisplayName("AuditController — thorough WebMvcTest")
@WebMvcTest(AuditController.class)
@Import({SecurityConfig.class, JacksonConfig.class})
class AuditControllerTest {

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

        // JwtAuthFilter must pass through to let @WithMockUser work
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

    // ── AC-4: all five permitted roles get 200 ────────────────────────────────

    @ParameterizedTest(name = "AC-4: role {0} gets HTTP 200")
    @ValueSource(strings = {
            RoleNames.OPS_ANALYST,
            RoleNames.INCIDENT_MANAGER,
            RoleNames.COMPLIANCE_OFFICER,
            RoleNames.CISO,
            RoleNames.BOARD_VIEWER
    })
    @Tag("AC-4")
    @DisplayName("AC-4: each permitted bank role receives HTTP 200")
    @WithMockUser(roles = "OPS_ANALYST")  // overridden by @WithMockUser via SecurityMockMvcRequestPostProcessors in test body
    void permittedRole_gets200(String role) throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("test@dora.local").roles(role)))
                .andExpect(status().isOk());
    }

    // ── AC-5: PLATFORM_ADMIN gets 403 ─────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: PLATFORM_ADMIN gets HTTP 403 (BR-011, NFR-009)")
    @WithMockUser(roles = {RoleNames.PLATFORM_ADMIN})
    void platformAdmin_gets403() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isForbidden());
    }

    // ── unauthenticated ───────────────────────────────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("Unauthenticated request gets HTTP 401")
    void unauthenticated_gets401() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ── missing required parameters ────────────────────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("Missing 'entity' parameter returns HTTP 400")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void missingEntity_gets400() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("id", entityId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("Missing 'id' parameter returns HTTP 400")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void missingId_gets400() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("Both required parameters missing returns HTTP 400")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void bothParamsMissing_gets400() throws Exception {
        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("Invalid UUID for 'id' parameter returns HTTP 400")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void invalidUuid_gets400() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("Blank (empty string) 'entity' rejected — @NotBlank fires ConstraintViolationException (no ControllerAdvice to map it to 400)")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void blankEntity_isRejected() {
        // @NotBlank on a @RequestParam triggers ConstraintViolationException at method-validation level.
        // Without a global @ControllerAdvice that maps ConstraintViolationException → 400, MockMvc
        // propagates the exception rather than returning a status code.
        // DEV RECOMMENDATION: add a ControllerAdvice mapping ConstraintViolationException → 400.
        // This test documents the current unhandled behaviour.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                mockMvc.perform(get("/api/v1/audit")
                                .param("entity", "")
                                .param("id", entityId.toString()))
                        .andReturn())
                .isInstanceOf(Exception.class);
    }

    // ── response shape ────────────────────────────────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("Successful 200 response has 'content' array JSON field")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void successfulResponse_hasContentArrayField() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("Successful 200 response has pagination fields: totalElements, totalPages, size, number")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void successfulResponse_hasPaginationFields() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists())
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.number").exists());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("Response content[0] has all expected AuditEntry fields")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void responseContentItem_hasAllAuditEntryFields() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("INCIDENT_CREATED"))
                .andExpect(jsonPath("$.content[0].entityType").value("INCIDENT"))
                .andExpect(jsonPath("$.content[0].actorUsername").value("ops@dora.local"))
                .andExpect(jsonPath("$.content[0].createdAt").exists());
    }

    // ── default pagination ─────────────────────────────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("When page and size not specified, service receives page=0 and size=20")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void defaultPagination_page0Size20_passedToService() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk());

        // Capture the Pageable passed to service and verify defaults
        var pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(auditService).findByEntity(eq("INCIDENT"), eq(entityId), pageableCaptor.capture());
        Pageable used = pageableCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(used.getPageNumber()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(used.getPageSize()).isEqualTo(20);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("When explicit page=2 and size=5 are specified, service receives them")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void explicitPagination_passedToService() throws Exception {
        when(auditService.findByEntity(eq("INCIDENT"), eq(entityId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(2, 5), 15));

        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString())
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        var pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(auditService).findByEntity(eq("INCIDENT"), eq(entityId), pageableCaptor.capture());
        Pageable used = pageableCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(used.getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(used.getPageSize()).isEqualTo(5);
    }

    // ── BOARD_VIEWER gets 200 (explicit, separate from parameterized) ─────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: BOARD_VIEWER role gets 200 with content array (read-only board member)")
    @WithMockUser(roles = {RoleNames.BOARD_VIEWER})
    void boardViewer_gets200WithContentArray() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── INCIDENT_MANAGER and BOARD_VIEWER (not covered as 200 in smoke tests) ──

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: INCIDENT_MANAGER role gets 200 with audit rows")
    @WithMockUser(roles = {RoleNames.INCIDENT_MANAGER})
    void incidentManager_gets200() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", entityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("Empty result (no audit rows for entity) returns 200 with empty content array")
    @WithMockUser(roles = {RoleNames.COMPLIANCE_OFFICER})
    void emptyResult_returns200WithEmptyContentArray() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(auditService.findByEntity(eq("INCIDENT"), eq(unknownId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/audit")
                        .param("entity", "INCIDENT")
                        .param("id", unknownId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
