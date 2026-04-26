package com.dora.thorough.audit;

import com.dora.dto.AuditEntry;
import com.dora.entities.AppUser;
import com.dora.entities.AuditLog;
import com.dora.entities.Tenant;
import com.dora.repositories.AuditLogRepository;
import com.dora.security.CustomUserDetails;
import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thorough unit tests for {@link AuditService}.
 *
 * <p>All tests use Mockito — no Spring context, no DB.
 * Observable output is what gets saved to the repository mock,
 * not internal state (ActorContext is private).
 */
@Tag("AC-1")
@DisplayName("AuditService — thorough unit tests")
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private ObjectMapper objectMapper;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        auditService = new AuditService(auditLogRepository, objectMapper);
    }

    @AfterEach
    void tearDown() {
        // Always clear security context after each test to avoid pollution
        SecurityContextHolder.clearContext();
    }

    // ── actor resolution ─────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("record() with authenticated CustomUserDetails saves correct tenantId and actorId")
    void record_withAuthenticatedUser_savesCorrectTenantAndActor() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String username = "ops@dora.local";

        setUpSecurityContext(tenantId, actorId, username);

        UUID entityId = UUID.randomUUID();
        auditService.record(AuditAction.INCIDENT_CREATED, "INCIDENT", entityId, null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getActorUsername()).isEqualTo(username);
        assertThat(saved.getAction()).isEqualTo("INCIDENT_CREATED");
        assertThat(saved.getEntityType()).isEqualTo("INCIDENT");
        assertThat(saved.getEntityId()).isEqualTo(entityId);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() with no SecurityContext sets actorId=null and actorUsername=SYSTEM")
    void record_withNoSecurityContext_setsSystemActor() {
        // No authentication set in context
        SecurityContextHolder.clearContext();

        auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getActorUsername()).isEqualTo("SYSTEM");
        assertThat(saved.getTenantId()).isNull();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() with authentication present but principal NOT a CustomUserDetails sets SYSTEM")
    void record_withNonCustomUserDetailsPrincipal_fallsBackToSystem() {
        // A valid but non-CustomUserDetails authentication (e.g., anonymous or test user)
        Authentication auth = new UsernamePasswordAuthenticationToken("anonymous", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getActorUsername()).isEqualTo("SYSTEM");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() with authenticated user whose Tenant is null stores null tenantId")
    void record_withNullTenant_storesNullTenantId() {
        // AppUser with no tenant (edge: user created before tenant assigned)
        UUID actorId = UUID.randomUUID();
        AppUser user = mock(AppUser.class);
        when(user.getTenant()).thenReturn(null);
        when(user.getId()).thenReturn(actorId);
        when(user.getUsername()).thenReturn("orphan@dora.local");

        CustomUserDetails details = new CustomUserDetails(user, List.of("OPS_ANALYST"));
        Authentication auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isNull();
    }

    // ── context JSONB shape (Q-2) ─────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("record() context JSONB has exactly three keys: request_id, remote_ip, user_agent — and NO others")
    void record_contextJsonb_hasExactlyThreeKeys() {
        // Outside request scope (no RequestContextHolder attributes)
        auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        com.fasterxml.jackson.databind.JsonNode context = captor.getValue().getContext();
        assertThat(context).isNotNull();

        // Must have exactly these three keys — no more, no less (Q-2 contract)
        assertThat(context.has("request_id")).isTrue();
        assertThat(context.has("remote_ip")).isTrue();
        assertThat(context.has("user_agent")).isTrue();

        // Count field names to ensure NO extras
        int fieldCount = 0;
        Iterator<String> fieldNames = context.fieldNames();
        while (fieldNames.hasNext()) {
            fieldNames.next();
            fieldCount++;
        }
        assertThat(fieldCount)
                .as("context JSONB must have exactly 3 fields (request_id, remote_ip, user_agent)")
                .isEqualTo(3);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() context fields are null-nodes (not absent) when outside request scope")
    void record_contextFields_areNullNodesOutsideRequestScope() {
        auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        com.fasterxml.jackson.databind.JsonNode ctx = captor.getValue().getContext();
        // Fields must be present as NullNode (not missing), so the schema is always consistent
        assertThat(ctx.get("request_id").isNull()).isTrue();
        assertThat(ctx.get("remote_ip").isNull()).isTrue();
        assertThat(ctx.get("user_agent").isNull()).isTrue();
    }

    // ── @Transactional propagation ────────────────────────────────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("record() is annotated @Transactional with default (REQUIRED) propagation — NOT REQUIRES_NEW")
    void record_transactionalAnnotation_usesRequiredPropagation() throws Exception {
        Method recordMethod = AuditService.class.getMethod(
                "record", AuditAction.class, String.class, UUID.class,
                com.fasterxml.jackson.databind.JsonNode.class,
                com.fasterxml.jackson.databind.JsonNode.class);

        // Check the class-level @Transactional (record() inherits it)
        Transactional classAnnotation = AuditService.class.getAnnotation(Transactional.class);
        assertThat(classAnnotation)
                .as("AuditService must have class-level @Transactional")
                .isNotNull();
        assertThat(classAnnotation.propagation())
                .as("Propagation must be REQUIRED (the default), never REQUIRES_NEW")
                .isEqualTo(Propagation.REQUIRED);

        // record() itself must NOT override with REQUIRES_NEW
        Transactional methodAnnotation = recordMethod.getAnnotation(Transactional.class);
        if (methodAnnotation != null) {
            assertThat(methodAnnotation.propagation())
                    .as("record() method-level @Transactional must not use REQUIRES_NEW")
                    .isNotEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    // ── findByEntity delegation ───────────────────────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("findByEntity() delegates to repository and maps AuditLog to AuditEntry correctly")
    void findByEntity_delegatesAndMapsCorrectly() {
        UUID entityId = UUID.randomUUID();
        UUID logId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        ObjectNode beforeNode = objectMapper.createObjectNode().put("status", "OPEN");
        ObjectNode afterNode = objectMapper.createObjectNode().put("status", "CLOSED");
        ObjectNode ctxNode = objectMapper.createObjectNode()
                .put("request_id", "req-1")
                .put("remote_ip", "127.0.0.1")
                .put("user_agent", "test-agent");

        AuditLog stubLog = new AuditLog(
                tenantId, actorId, "ops@dora.local",
                "INCIDENT_CLOSED", "INCIDENT", entityId,
                beforeNode, afterNode, ctxNode);
        // Simulate what JPA would set (id is normally GeneratedValue, set via reflection for test)
        setIdViaReflection(stubLog, logId);
        // createdAt is set by DB, simulate via reflection
        setCreatedAtViaReflection(stubLog, now);

        Page<AuditLog> repoPage = new PageImpl<>(List.of(stubLog));
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                eq("INCIDENT"), eq(entityId), any(Pageable.class)))
                .thenReturn(repoPage);

        Page<AuditEntry> result = auditService.findByEntity("INCIDENT", entityId, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1L);
        AuditEntry entry = result.getContent().get(0);
        assertThat(entry.id()).isEqualTo(logId);
        assertThat(entry.tenantId()).isEqualTo(tenantId);
        assertThat(entry.actorId()).isEqualTo(actorId);
        assertThat(entry.actorUsername()).isEqualTo("ops@dora.local");
        assertThat(entry.action()).isEqualTo("INCIDENT_CLOSED");
        assertThat(entry.entityType()).isEqualTo("INCIDENT");
        assertThat(entry.entityId()).isEqualTo(entityId);
        assertThat(entry.beforeState()).isEqualTo(beforeNode);
        assertThat(entry.afterState()).isEqualTo(afterNode);
        assertThat(entry.context()).isEqualTo(ctxNode);
        assertThat(entry.createdAt()).isEqualTo(now);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("findByEntity() passes entityType and entityId through to repository unchanged")
    void findByEntity_passesParamsToRepository() {
        String entityType = "REPORT";
        UUID entityId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(2, 5);

        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                eq(entityType), eq(entityId), eq(pageable)))
                .thenReturn(Page.empty());

        auditService.findByEntity(entityType, entityId, pageable);

        verify(auditLogRepository).findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType, entityId, pageable);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() passes null entityId through to AuditLog without NPE (non-entity events)")
    void record_withNullEntityId_passesNullToRepository() {
        auditService.record(AuditAction.DEADLINE_ALERT_SENT, "PROBE", null, null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEntityId()).isNull();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() passes before and after JsonNode snapshots to AuditLog correctly")
    void record_withBeforeAndAfterNodes_storesBoth() {
        ObjectNode before = objectMapper.createObjectNode().put("status", "OPEN");
        ObjectNode after = objectMapper.createObjectNode().put("status", "INVESTIGATING");
        UUID entityId = UUID.randomUUID();

        auditService.record(AuditAction.INCIDENT_UPDATED, "INCIDENT", entityId, before, after);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getBeforeState()).isEqualTo(before);
        assertThat(captor.getValue().getAfterState()).isEqualTo(after);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("findByEntity() with empty result returns empty Page (no NPE)")
    void findByEntity_emptyPage_returnsEmptyPage() {
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<AuditEntry> result = auditService.findByEntity("INCIDENT", UUID.randomUUID(), PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
    }

    // ── MDC / request context coverage ───────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("record() context.request_id is populated from MDC key 'requestId' when present")
    void record_withMdcRequestId_populatesRequestIdInContext() {
        org.slf4j.MDC.put("requestId", "test-req-123");
        try {
            auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);
        } finally {
            org.slf4j.MDC.remove("requestId");
        }

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        com.fasterxml.jackson.databind.JsonNode ctx = captor.getValue().getContext();
        assertThat(ctx.get("request_id").asText()).isEqualTo("test-req-123");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() context.request_id is null-node when MDC has no 'requestId' key")
    void record_withoutMdcRequestId_requestIdIsNullNode() {
        org.slf4j.MDC.remove("requestId"); // ensure absent
        auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getContext().get("request_id").isNull()).isTrue();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() context.remote_ip and user_agent are null-nodes when no servlet request is available")
    void record_withoutServletRequest_remoteIpAndUserAgentAreNullNodes() {
        // RequestContextHolder is empty (no active request) — this is the default in unit tests
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        com.fasterxml.jackson.databind.JsonNode ctx = captor.getValue().getContext();
        assertThat(ctx.get("remote_ip").isNull()).isTrue();
        assertThat(ctx.get("user_agent").isNull()).isTrue();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() context is populated with remote_ip and user_agent from active ServletRequest")
    void record_withActiveServletRequest_populatesRemoteIpAndUserAgent() {
        // Simulate an active HTTP request via RequestContextHolder
        org.springframework.mock.web.MockHttpServletRequest mockRequest =
                new org.springframework.mock.web.MockHttpServletRequest();
        mockRequest.setRemoteAddr("10.0.0.1");
        mockRequest.addHeader("User-Agent", "Mozilla/5.0");
        org.springframework.web.context.request.ServletRequestAttributes attrs =
                new org.springframework.web.context.request.ServletRequestAttributes(mockRequest);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);

        try {
            auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        com.fasterxml.jackson.databind.JsonNode ctx = captor.getValue().getContext();
        assertThat(ctx.get("remote_ip").asText()).isEqualTo("10.0.0.1");
        assertThat(ctx.get("user_agent").asText()).isEqualTo("Mozilla/5.0");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("record() context.user_agent is null-node when User-Agent header is absent")
    void record_withServletRequestButNoUserAgent_userAgentIsNullNode() {
        org.springframework.mock.web.MockHttpServletRequest mockRequest =
                new org.springframework.mock.web.MockHttpServletRequest();
        mockRequest.setRemoteAddr("192.168.1.1");
        // No User-Agent header set
        org.springframework.web.context.request.ServletRequestAttributes attrs =
                new org.springframework.web.context.request.ServletRequestAttributes(mockRequest);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);

        try {
            auditService.record(AuditAction.SYSTEM, "PROBE", UUID.randomUUID(), null, null);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        com.fasterxml.jackson.databind.JsonNode ctx = captor.getValue().getContext();
        assertThat(ctx.get("remote_ip").asText()).isEqualTo("192.168.1.1");
        assertThat(ctx.get("user_agent").isNull()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setUpSecurityContext(UUID tenantId, UUID actorId, String username) {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);

        AppUser user = mock(AppUser.class);
        when(user.getTenant()).thenReturn(tenant);
        when(user.getId()).thenReturn(actorId);
        when(user.getUsername()).thenReturn(username);

        CustomUserDetails details = new CustomUserDetails(user, List.of("OPS_ANALYST"));
        Authentication auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void setIdViaReflection(AuditLog log, UUID id) {
        try {
            var field = AuditLog.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(log, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setCreatedAtViaReflection(AuditLog log, Instant createdAt) {
        try {
            var field = AuditLog.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(log, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
