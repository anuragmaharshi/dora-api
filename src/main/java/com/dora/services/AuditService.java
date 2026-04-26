package com.dora.services;

import com.dora.dto.AuditEntry;
import com.dora.entities.AuditLog;
import com.dora.repositories.AuditLogRepository;
import com.dora.security.CustomUserDetails;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Single authoritative write path to {@code audit_log} (LLD-03 §4).
 *
 * <p>This is the only component in the codebase permitted to insert into {@code audit_log}.
 * All other services that need audit rows call this service; they do not touch
 * {@link AuditLogRepository} directly.
 *
 * <h2>Transaction propagation — REQUIRED (not REQUIRES_NEW)</h2>
 * {@code @Transactional} defaults to {@code Propagation.REQUIRED}: the method enrols in the
 * caller's active transaction. This is intentional per LLD-03 AC-3 and D-LLD03-1: if the
 * business transaction rolls back, the audit row is never committed.  Using
 * {@code REQUIRES_NEW} would commit the audit row even on rollback, which would create a
 * false record of an action that never actually happened — a data integrity violation.
 *
 * <h2>Context JSONB scope (DECIDE Q-2)</h2>
 * The {@code context} JSONB column stores exactly three fields:
 * <ol>
 *   <li>{@code request_id} — from MDC key {@code "requestId"} (set by a request filter).</li>
 *   <li>{@code remote_ip} — from {@link HttpServletRequest#getRemoteAddr()}.</li>
 *   <li>{@code user_agent} — from the {@code User-Agent} request header.</li>
 * </ol>
 * No other headers are captured. PII minimisation rationale: raw headers may expose
 * session cookies, correlation tokens, or vendor metadata.
 *
 * <h2>Actor resolution</h2>
 * Tenant and actor are read from the Spring Security context on every call. If there is
 * no authenticated principal (e.g. during a SYSTEM action or an unauthenticated event),
 * {@code actor_id} is {@code null} and {@code actor_username} defaults to {@code "SYSTEM"}.
 */
@Service
@Transactional
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an audit event in the caller's transaction.
     *
     * @param action     the action that occurred
     * @param entityType the entity type discriminator (e.g. "INCIDENT")
     * @param entityId   the affected entity's UUID, or {@code null} for non-entity events
     * @param before     the entity state before the change, or {@code null} for creation events
     * @param after      the entity state after the change, or {@code null} for deletion events
     */
    public void record(AuditAction action,
                       String entityType,
                       UUID entityId,
                       JsonNode before,
                       JsonNode after) {

        ActorContext actor = resolveActor();
        JsonNode context = buildContext();

        AuditLog log = new AuditLog(
                actor.tenantId(),
                actor.actorId(),
                actor.actorUsername(),
                action.name(),
                entityType,
                entityId,
                before,
                after,
                context
        );

        auditLogRepository.save(log);
    }

    /**
     * Returns a paginated view of audit history for a specific entity, newest first.
     *
     * <p>Read-only: uses the default transaction (REQUIRED) but does not write.
     */
    @Transactional(readOnly = true)
    public Page<AuditEntry> findByEntity(String entityType, UUID entityId, Pageable pageable) {
        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId, pageable)
                .map(this::toEntry);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /**
     * Reads the authenticated principal from the Security context.
     *
     * <p>Returns a SYSTEM actor tuple when there is no authenticated principal, which
     * happens for background jobs, scheduled tasks, and test scenarios that call
     * {@code record()} outside an HTTP request.
     */
    private ActorContext resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof CustomUserDetails ud) {
            UUID tenantId = ud.getAppUser().getTenant() != null
                    ? ud.getAppUser().getTenant().getId()
                    : null;
            return new ActorContext(tenantId, ud.getAppUser().getId(), ud.getAppUser().getUsername());
        }
        // SYSTEM action or unauthenticated context
        return new ActorContext(null, null, "SYSTEM");
    }

    /**
     * Builds the context JSONB with exactly {request_id, remote_ip, user_agent}.
     *
     * <p>All three fields are best-effort: if the request is not available (e.g. in
     * background job context), the fields are set to {@code null} nodes rather than
     * omitting them, so the schema is always consistent.
     */
    private JsonNode buildContext() {
        ObjectNode ctx = objectMapper.createObjectNode();

        // request_id — from MDC (typically set by a logging filter per correlation-id convention)
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            ctx.put("request_id", requestId);
        } else {
            ctx.putNull("request_id");
        }

        // remote_ip and user_agent — from current HTTP servlet request, if available
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                ctx.put("remote_ip", request.getRemoteAddr());
                String ua = request.getHeader("User-Agent");
                if (ua != null) {
                    ctx.put("user_agent", ua);
                } else {
                    ctx.putNull("user_agent");
                }
            } else {
                ctx.putNull("remote_ip");
                ctx.putNull("user_agent");
            }
        } catch (Exception ex) {
            // Outside request scope (batch jobs, async threads) — degrade gracefully.
            ctx.putNull("remote_ip");
            ctx.putNull("user_agent");
        }

        return ctx;
    }

    private AuditEntry toEntry(AuditLog log) {
        return new AuditEntry(
                log.getId(),
                log.getTenantId(),
                log.getActorId(),
                log.getActorUsername(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getBeforeState(),
                log.getAfterState(),
                log.getContext(),
                log.getCreatedAt()
        );
    }

    /**
     * Value type capturing the resolved actor identity from the Security context.
     * Using a record here keeps the return type immutable and avoids a bare Object[].
     */
    private record ActorContext(UUID tenantId, UUID actorId, String actorUsername) {}
}
