package com.dora.security;

import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Defence-in-depth firewall for the PLATFORM_ADMIN role (LLD-04 §2, AC-6, BR-011, NFR-009).
 *
 * <p>If the authenticated user holds the {@code ROLE_PLATFORM_ADMIN} authority AND the
 * request path is NOT in the allowed whitelist, this filter short-circuits with HTTP 403
 * and records a {@link AuditAction#PLATFORM_ADMIN_BLOCKED_PATH} entry in the audit log.
 *
 * <p>This filter is placed AFTER {@link JwtAuthFilter} (which populates the Security context)
 * and BEFORE the Spring MVC dispatch. The ordering is achieved by registering it in
 * {@link SecurityConfig} via {@code addFilterAfter(jwtAuthFilter, ...)} — not via
 * Spring component scan ordering, which is non-deterministic.
 *
 * <h2>Whitelist rationale</h2>
 * <ul>
 *   <li>{@code /api/v1/admin/**} — the PLATFORM_ADMIN's own endpoints</li>
 *   <li>{@code /api/v1/auth/**} — login, logout, token refresh (role-agnostic)</li>
 *   <li>{@code /actuator/health} — health probe; no business data</li>
 *   <li>{@code /swagger-ui/**}, {@code /v3/api-docs/**}, {@code /openapi.yaml} — dev tooling</li>
 * </ul>
 *
 * <p>All other paths — incidents, reports, audit trail, dashboard — must return 403 for
 * PLATFORM_ADMIN regardless of any {@code @PreAuthorize} annotation on the handler.
 */
@Component
public class PlatformAdminFirewallFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminFirewallFilter.class);

    /**
     * Path prefixes that PLATFORM_ADMIN is permitted to access.
     * Evaluated in order; the first match allows the request through.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/api/v1/admin/",
            "/api/v1/auth/",
            "/actuator/health",
            "/swagger-ui/",
            "/v3/api-docs/",
            "/openapi.yaml"
    );

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * AuditService is injected lazily so that @WebMvcTest slices (which don't load the
     * service layer) can construct this filter without a missing-bean error. In production
     * the real bean is always present; in @WebMvcTest slices with mocked security the filter
     * is registered but the audit call is wrapped in try-catch so a null audit service
     * won't prevent the 403 response.
     */
    public PlatformAdminFirewallFilter(@Lazy AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null
                && auth.isAuthenticated()
                && hasPlatformAdminRole(auth)
                && !isAllowedPath(request.getRequestURI())) {

            // Log the blocked attempt — this is a security event per BR-011
            log.warn("PLATFORM_ADMIN firewall blocked path: {} for principal: {}",
                    request.getRequestURI(), auth.getName());

            // Audit within the same transaction context (Propagation.REQUIRED joins if one exists)
            recordBlockedPathAudit(request);

            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");

            ObjectNode body = objectMapper.createObjectNode();
            body.put("status", 403);
            body.put("error", "Forbidden");
            body.put("message", "PLATFORM_ADMIN role is not permitted to access this resource");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return; // short-circuit — do not continue the filter chain
        }

        filterChain.doFilter(request, response);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private boolean hasPlatformAdminRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + RoleNames.PLATFORM_ADMIN).equals(a.getAuthority()));
    }

    private boolean isAllowedPath(String uri) {
        return ALLOWED_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    /**
     * Records the blocked-path event to the audit log.
     *
     * <p>We wrap in try-catch so that an audit failure does not prevent the 403 response
     * from being sent — the firewall must always block regardless of audit health.
     */
    private void recordBlockedPathAudit(HttpServletRequest request) {
        try {
            ObjectNode context = objectMapper.createObjectNode();
            context.put("blocked_path", request.getRequestURI());
            context.put("method", request.getMethod());
            auditService.record(
                    AuditAction.PLATFORM_ADMIN_BLOCKED_PATH,
                    "FIREWALL",
                    null,
                    null,
                    context
            );
        } catch (Exception ex) {
            // Audit failure must not affect the blocking decision — log and continue
            log.error("Failed to record PLATFORM_ADMIN_BLOCKED_PATH audit event", ex);
        }
    }
}
