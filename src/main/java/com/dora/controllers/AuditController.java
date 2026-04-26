package com.dora.controllers;

import com.dora.dto.AuditEntry;
import com.dora.security.RoleNames;
import com.dora.services.AuditService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only audit trail endpoint (LLD-03 §4, FR-029).
 *
 * <p>Exposes {@code GET /api/v1/audit} with pagination. Returns the audit history for a
 * specific entity, newest row first.
 *
 * <h2>Authorisation (AC-4, AC-5, BR-011)</h2>
 * Permitted roles: OPS_ANALYST, INCIDENT_MANAGER, COMPLIANCE_OFFICER, CISO, BOARD_VIEWER.
 * PLATFORM_ADMIN is explicitly excluded per BR-011 / NFR-009 — that role is a service-company
 * operator and must have zero visibility into bank incident data.
 * The {@code @PreAuthorize} expression references {@link RoleNames} constants to avoid
 * inline literal strings (LLD-03 §4, reviewer gate).
 *
 * <h2>No controller-level side calls</h2>
 * All business logic and data access is delegated to {@link AuditService}. This controller
 * does only: auth guard, parameter validation, pagination assembly, service delegation,
 * and response serialisation.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Validated
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Returns audit history for an entity, paginated, newest first.
     *
     * <p>AC-4: 200 + rows for permitted bank roles.
     * <p>AC-5: 403 for PLATFORM_ADMIN (excluded from the allow-list).
     *
     * @param entity the entity type discriminator (e.g. "INCIDENT", "PROBE")
     * @param id     the entity's UUID
     * @param page   0-based page index (default 0)
     * @param size   page size (default 20)
     * @return paginated audit rows, newest first
     */
    @GetMapping
    @PreAuthorize(
        "hasAnyRole('" + RoleNames.OPS_ANALYST + "', "
              + "'" + RoleNames.INCIDENT_MANAGER + "', "
              + "'" + RoleNames.COMPLIANCE_OFFICER + "', "
              + "'" + RoleNames.CISO + "', "
              + "'" + RoleNames.BOARD_VIEWER + "')"
    )
    public ResponseEntity<Page<AuditEntry>> getAuditHistory(
            @RequestParam("entity") @NotBlank String entity,
            @RequestParam("id") @NotNull UUID id,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<AuditEntry> result = auditService.findByEntity(entity, id, pageable);
        return ResponseEntity.ok(result);
    }
}
