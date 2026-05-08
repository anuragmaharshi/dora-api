package com.dora.incidents.web;

import com.dora.incidents.api.dto.AttachmentResponse;
import com.dora.incidents.api.dto.CreateIncidentRequest;
import com.dora.incidents.api.dto.IctAssetResponse;
import com.dora.incidents.api.dto.IncidentResponse;
import com.dora.incidents.api.dto.IncidentSummary;
import com.dora.incidents.api.dto.LinkAssetRequest;
import com.dora.incidents.api.dto.LinkServicesRequest;
import com.dora.incidents.api.dto.PresignedUploadResponse;
import com.dora.incidents.api.dto.RequestAttachmentUpload;
import com.dora.incidents.application.IncidentService;
import com.dora.security.CustomUserDetails;
import com.dora.security.RoleNames;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Incident Logging endpoints (LLD-05 §4).
 *
 * <p>Authorization table (LLD-05 §4, resolved decisions from STATE.md):
 * <ul>
 *   <li>POST   /incidents              — OPS_ANALYST, INCIDENT_MANAGER, COMPLIANCE_OFFICER, CISO</li>
 *   <li>GET    /incidents/{id}         — OPS_ANALYST, INCIDENT_MANAGER, COMPLIANCE_OFFICER, CISO (BOARD_VIEWER excluded per BLOCKER-1)</li>
 *   <li>GET    /incidents              — OPS_ANALYST, INCIDENT_MANAGER, COMPLIANCE_OFFICER, CISO (BOARD_VIEWER excluded)</li>
 *   <li>POST   /incidents/{id}/attachments          — OPS_ANALYST, INCIDENT_MANAGER</li>
 *   <li>POST   /incidents/{id}/attachments/{id}/complete — OPS_ANALYST, INCIDENT_MANAGER</li>
 *   <li>POST   /incidents/{id}/services — OPS_ANALYST, INCIDENT_MANAGER</li>
 *   <li>POST   /incidents/{id}/assets  — OPS_ANALYST, INCIDENT_MANAGER</li>
 * </ul>
 *
 * <p>PLATFORM_ADMIN gets 403 on all these paths via {@link com.dora.security.PlatformAdminFirewallFilter}
 * (AC-8, BR-011, NFR-009). The @PreAuthorize expressions do not mention PLATFORM_ADMIN — exclusion
 * is by omission. The firewall filter provides the explicit block.
 *
 * <p>Tenant isolation: tenantId is always extracted from the authenticated principal —
 * it is never accepted as a request parameter.
 *
 * <p>No controller-level HTTP calls. No JPA entities in response bodies.
 */
@RestController
@RequestMapping("/api/v1/incidents")
@Validated
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    /**
     * AC-1: Create a new incident.
     * PLATFORM_ADMIN and BOARD_VIEWER are excluded by omission (BLOCKER-1 resolution).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('" + RoleNames.OPS_ANALYST + "','"
            + RoleNames.INCIDENT_MANAGER + "','"
            + RoleNames.COMPLIANCE_OFFICER + "','"
            + RoleNames.CISO + "')")
    public ResponseEntity<IncidentResponse> createIncident(
            @Valid @RequestBody CreateIncidentRequest request,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        UUID actorId = principal.getAppUser().getId();
        IncidentResponse response = incidentService.create(request, tenantId, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * AC-6: Get full incident detail.
     * BOARD_VIEWER excluded per BLOCKER-1 resolution in STATE.md (deferred to LLD-14).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + RoleNames.OPS_ANALYST + "','"
            + RoleNames.INCIDENT_MANAGER + "','"
            + RoleNames.COMPLIANCE_OFFICER + "','"
            + RoleNames.CISO + "')")
    public ResponseEntity<IncidentResponse> getIncident(
            @PathVariable UUID id,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(incidentService.findById(id, tenantId));
    }

    /**
     * Basic paginated incident list. Rich search/filter deferred to LLD-14.
     * BOARD_VIEWER excluded per BLOCKER-1.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('" + RoleNames.OPS_ANALYST + "','"
            + RoleNames.INCIDENT_MANAGER + "','"
            + RoleNames.COMPLIANCE_OFFICER + "','"
            + RoleNames.CISO + "')")
    public ResponseEntity<Page<IncidentSummary>> listIncidents(
            Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(incidentService.list(tenantId, pageable));
    }

    /**
     * AC-3 step 1: Request a presigned upload URL for an attachment.
     */
    @PostMapping("/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('" + RoleNames.OPS_ANALYST + "','" + RoleNames.INCIDENT_MANAGER + "')")
    public ResponseEntity<PresignedUploadResponse> requestAttachmentUpload(
            @PathVariable UUID id,
            @Valid @RequestBody RequestAttachmentUpload request,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        UUID actorId = principal.getAppUser().getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(incidentService.addAttachment(id, request, tenantId, actorId));
    }

    /**
     * AC-3 step 3: Confirm the upload completed and flip attachment status to READY.
     */
    @PostMapping("/{id}/attachments/{attachmentId}/complete")
    @PreAuthorize("hasAnyRole('" + RoleNames.OPS_ANALYST + "','" + RoleNames.INCIDENT_MANAGER + "')")
    public ResponseEntity<AttachmentResponse> completeAttachment(
            @PathVariable UUID id,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(incidentService.completeAttachment(id, attachmentId, tenantId));
    }

    /**
     * AC-4: Link active critical services to an incident.
     */
    @PostMapping("/{id}/services")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('" + RoleNames.OPS_ANALYST + "','" + RoleNames.INCIDENT_MANAGER + "')")
    public ResponseEntity<Void> linkServices(
            @PathVariable UUID id,
            @Valid @RequestBody LinkServicesRequest request,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        incidentService.linkServices(id, request, tenantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * AC-5: Link an ICT asset to an incident.
     */
    @PostMapping("/{id}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('" + RoleNames.OPS_ANALYST + "','" + RoleNames.INCIDENT_MANAGER + "')")
    public ResponseEntity<IctAssetResponse> linkAsset(
            @PathVariable UUID id,
            @Valid @RequestBody LinkAssetRequest request,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(incidentService.linkAsset(id, request, tenantId));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts tenant ID from the authenticated principal.
     * Every bank-role user is associated with exactly one tenant.
     */
    private UUID resolveTenantId(CustomUserDetails principal) {
        if (principal.getAppUser().getTenant() == null) {
            throw new IllegalStateException(
                    "Authenticated user has no tenant association — data integrity violation");
        }
        return principal.getAppUser().getTenant().getId();
    }
}
