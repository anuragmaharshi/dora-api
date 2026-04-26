package com.dora.support;

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
 * Test-only audit row producer for integration tests (LLD-03 §7, W2 dispatch).
 *
 * <p>Active only under the {@code test} Spring profile. This controller allows integration
 * and E2E tests to emit a real audit row without depending on LLD-05 incident endpoints
 * (which do not exist yet).
 *
 * <p>Security: {@code permitAll()} because this endpoint is only reachable in the test
 * profile and is never deployed to staging or production. The controller class itself is
 * excluded from the production build by {@code @Profile("test")}.
 *
 * <p>Usage in tests:
 * <pre>{@code
 * POST /api/v1/_test/audit-emit?entity_type=PROBE&entity_id=<uuid>&action=INCIDENT_CREATED
 * }</pre>
 */
@Profile("test")
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
