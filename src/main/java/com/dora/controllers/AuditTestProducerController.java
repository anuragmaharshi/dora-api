package com.dora.controllers;

import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Audit row producer for E2E tests (LLD-03 §7, LLD-05 fix-wave).
 *
 * <p>Active only under the {@code e2e} Spring profile. Compiling this into the main JAR
 * (gated by the profile) means the endpoint is reachable in the Docker Compose E2E stack
 * where {@code SPRING_PROFILES_ACTIVE=default,e2e} is set. It is never active in
 * production (profile guard) or in unit/integration test runs (those use the {@code test}
 * profile, not {@code e2e}).
 *
 * <p>Security: {@code permitAll()} is intentional — E2E scenarios call this unauthenticated
 * to seed audit rows as a test precondition. The {@code e2e} profile is only activated in
 * controlled test environments; it must never be activated in staging or production.
 *
 * <p>Usage:
 * <pre>{@code
 * POST /api/v1/_test/audit-emit?entity_type=PROBE&entity_id=<uuid>&action=INCIDENT_CREATED
 * }</pre>
 */
@Profile("e2e")
@RestController
@RequestMapping("/api/v1/_test")
public class AuditTestProducerController {

    private final AuditService auditService;

    public AuditTestProducerController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Emits a single audit row with the supplied parameters.
     *
     * @param entityType the entity type discriminator (e.g. "PROBE", "INCIDENT")
     * @param entityId   the UUID to record as the affected entity
     * @param action     the AuditAction enum name (defaults to SYSTEM)
     * @return 200 with the entity_id echoed back for assertion
     */
    @PostMapping("/audit-emit")
    @PreAuthorize("permitAll()")
    public ResponseEntity<String> emitAuditRow(
            @RequestParam("entity_type") String entityType,
            @RequestParam("entity_id") UUID entityId,
            @RequestParam(value = "action", defaultValue = "SYSTEM") String action) {

        AuditAction auditAction;
        try {
            auditAction = AuditAction.valueOf(action);
        } catch (IllegalArgumentException ex) {
            auditAction = AuditAction.SYSTEM;
        }

        auditService.record(auditAction, entityType, entityId, null, null);
        return ResponseEntity.ok("emitted:" + entityId);
    }
}
