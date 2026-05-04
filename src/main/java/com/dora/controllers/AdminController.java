package com.dora.controllers;

import com.dora.dto.ClientBaseEntryDTO;
import com.dora.dto.ClientBaseHistoryDTO;
import com.dora.dto.CreateCriticalServiceDTO;
import com.dora.dto.CriticalServiceDTO;
import com.dora.dto.NcaEmailConfigDTO;
import com.dora.dto.NcaEmailConfigUpdateDTO;
import com.dora.dto.SetClientBaseDTO;
import com.dora.dto.TenantConfigDTO;
import com.dora.dto.TenantConfigUpdateDTO;
import com.dora.dto.UpdateCriticalServiceDTO;
import com.dora.security.CustomUserDetails;
import com.dora.security.RoleNames;
import com.dora.services.ClientBaseService;
import com.dora.services.CriticalServiceService;
import com.dora.services.NcaEmailService;
import com.dora.services.TenantConfigService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * All 10 admin endpoints for the PLATFORM_ADMIN role (LLD-04 §4, AC-1 through AC-8).
 *
 * <p>Every endpoint is guarded by both:
 * <ol>
 *   <li>{@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")} — Spring method security</li>
 *   <li>{@link com.dora.security.PlatformAdminFirewallFilter} — cross-cutting firewall
 *       that rejects PLATFORM_ADMIN on any non-admin path for defence-in-depth (AC-6)</li>
 * </ol>
 *
 * <p>The tenantId is extracted from the authenticated principal's AppUser. PLATFORM_ADMIN
 * users are seeded with the same Nexus Bank tenant (00000000-0000-0000-0000-000000000001)
 * in the dev environment, and provisioned via the onboarding flow in production.
 *
 * <p>No controller-level HTTP calls. No JPA leaks (entities are never serialised directly).
 */
@RestController
@RequestMapping("/api/v1/admin")
@Validated
@PreAuthorize("hasRole('" + RoleNames.PLATFORM_ADMIN + "')")
public class AdminController {

    private final TenantConfigService tenantConfigService;
    private final CriticalServiceService criticalServiceService;
    private final ClientBaseService clientBaseService;
    private final NcaEmailService ncaEmailService;

    public AdminController(TenantConfigService tenantConfigService,
                           CriticalServiceService criticalServiceService,
                           ClientBaseService clientBaseService,
                           NcaEmailService ncaEmailService) {
        this.tenantConfigService = tenantConfigService;
        this.criticalServiceService = criticalServiceService;
        this.clientBaseService = clientBaseService;
        this.ncaEmailService = ncaEmailService;
    }

    // ── Tenant Config ──────────────────────────────────────────────────────────

    /**
     * AC-1: Returns the tenant's current configuration.
     */
    @GetMapping("/tenant")
    public ResponseEntity<TenantConfigDTO> getTenantConfig(
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(tenantConfigService.getTenant(tenantId));
    }

    /**
     * AC-1: Updates the tenant's configuration. Returns the updated view.
     */
    @PutMapping("/tenant")
    public ResponseEntity<TenantConfigDTO> updateTenantConfig(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody TenantConfigUpdateDTO dto) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(tenantConfigService.updateTenant(tenantId, dto));
    }

    // ── Critical Services ──────────────────────────────────────────────────────

    /**
     * AC-2: Returns all critical services (including archived) for the tenant.
     */
    @GetMapping("/critical-services")
    public ResponseEntity<List<CriticalServiceDTO>> listCriticalServices(
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(criticalServiceService.list(tenantId));
    }

    /**
     * AC-2: Creates a new critical service.
     */
    @PostMapping("/critical-services")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<CriticalServiceDTO> createCriticalService(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody CreateCriticalServiceDTO dto) {

        UUID tenantId = resolveTenantId(principal);
        CriticalServiceDTO created = criticalServiceService.create(tenantId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * AC-2: Updates the name or description of an existing critical service.
     */
    @PutMapping("/critical-services/{id}")
    public ResponseEntity<CriticalServiceDTO> updateCriticalService(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCriticalServiceDTO dto) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(criticalServiceService.update(tenantId, id, dto));
    }

    /**
     * AC-2: Archives a critical service (sets active=false). Idempotent.
     * Returns 204 No Content on success.
     */
    @PostMapping("/critical-services/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> archiveCriticalService(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID id) {

        UUID tenantId = resolveTenantId(principal);
        criticalServiceService.archive(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Client Base ────────────────────────────────────────────────────────────

    /**
     * AC-3: Returns the full client base history for the tenant, most recent first.
     */
    @GetMapping("/client-base")
    public ResponseEntity<ClientBaseHistoryDTO> getClientBaseHistory(
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(clientBaseService.getHistory(tenantId));
    }

    /**
     * AC-3: Appends a new client base count entry. The effective date is specified by the caller.
     */
    @PostMapping("/client-base")
    public ResponseEntity<ClientBaseEntryDTO> addClientBaseEntry(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody SetClientBaseDTO dto) {

        UUID tenantId = resolveTenantId(principal);
        UUID actorId = principal.getAppUser().getId();
        ClientBaseEntryDTO created = clientBaseService.addEntry(tenantId, actorId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── NCA Email Config ───────────────────────────────────────────────────────

    /**
     * AC-4: Returns the current NCA email configuration.
     * Returns an empty DTO (all nulls) if not yet configured.
     */
    @GetMapping("/nca-email")
    public ResponseEntity<NcaEmailConfigDTO> getNcaEmailConfig(
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(ncaEmailService.getConfig(tenantId));
    }

    /**
     * AC-4: Creates or updates the NCA email configuration.
     */
    @PutMapping("/nca-email")
    public ResponseEntity<NcaEmailConfigDTO> updateNcaEmailConfig(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody NcaEmailConfigUpdateDTO dto) {

        UUID tenantId = resolveTenantId(principal);
        return ResponseEntity.ok(ncaEmailService.updateConfig(tenantId, dto));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * Extracts the tenant ID from the authenticated principal.
     *
     * <p>Every PLATFORM_ADMIN is associated with exactly one tenant. This is enforced at
     * user creation time in the onboarding flow. If tenant is null, the data is corrupted —
     * fail fast with a clear message rather than a NPE or a wrong-tenant query.
     */
    private UUID resolveTenantId(CustomUserDetails principal) {
        if (principal.getAppUser().getTenant() == null) {
            throw new IllegalStateException(
                    "Authenticated PLATFORM_ADMIN has no tenant association — data integrity violation");
        }
        return principal.getAppUser().getTenant().getId();
    }
}
