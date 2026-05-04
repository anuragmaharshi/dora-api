package com.dora.services;

import com.dora.dto.TenantConfigDTO;
import com.dora.dto.TenantConfigUpdateDTO;
import com.dora.entities.Tenant;
import com.dora.repositories.TenantRepository;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Business logic for the tenant configuration screen (LLD-04 §4, AC-1, AC-8).
 *
 * <p>This service owns reads and writes to the {@code tenant} table's LLD-04 extension columns.
 * It does not touch the structural fields created in LLD-02 (legalName, lei) — they are
 * writable here only because PLATFORM_ADMIN is explicitly allowed to correct them.
 *
 * <p>Every mutation calls {@link AuditService#record} within the same transaction so an
 * audit row is committed atomically with the data change (AC-8 / LLD-03 propagation rule).
 */
@Service
@Transactional
public class TenantConfigService {

    private final TenantRepository tenantRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public TenantConfigService(TenantRepository tenantRepository,
                                AuditService auditService,
                                ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the tenant configuration view for the admin UI.
     *
     * @param tenantId the authenticated platform admin's tenant id
     * @return DTO with all configurable fields
     * @throws IllegalStateException if the tenant record does not exist (should not happen in practice)
     */
    @Transactional(readOnly = true)
    public TenantConfigDTO getTenant(UUID tenantId) {
        Tenant tenant = loadTenant(tenantId);
        return toDTO(tenant);
    }

    /**
     * Applies a partial update to the tenant record.
     *
     * <p>All fields in the DTO are applied, including nulls — the caller is responsible for
     * sending only the values they wish to set. This is a full-replace semantics on the
     * configurable subset; the immutable fields (id, createdAt) are never touched.
     *
     * @param tenantId the tenant to update
     * @param dto      the new values
     * @return the updated configuration view
     */
    public TenantConfigDTO updateTenant(UUID tenantId, TenantConfigUpdateDTO dto) {
        Tenant tenant = loadTenant(tenantId);
        JsonNode before = objectMapper.valueToTree(toDTO(tenant));

        tenant.setLegalName(dto.legalName());
        tenant.setLei(dto.lei());
        tenant.setNcaName(dto.ncaName());
        tenant.setNcaEmail(dto.ncaEmail());
        tenant.setJurisdictionIso(dto.jurisdictionIso());
        tenant.setPrimaryComplianceContactId(dto.primaryComplianceContactId());

        Tenant saved = tenantRepository.save(tenant);
        JsonNode after = objectMapper.valueToTree(toDTO(saved));

        auditService.record(
                AuditAction.TENANT_CONFIG_UPDATED,
                "TENANT",
                tenantId,
                before,
                after
        );

        return toDTO(saved);
    }

    /**
     * Returns the full tenant config — used by later LLDs (LLD-10/11 NCA report pre-fill).
     */
    @Transactional(readOnly = true)
    public TenantConfigDTO getCurrent(UUID tenantId) {
        return getTenant(tenantId);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private Tenant loadTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Tenant not found: " + tenantId + " — data integrity violation"));
    }

    private TenantConfigDTO toDTO(Tenant t) {
        return new TenantConfigDTO(
                t.getId(),
                t.getLegalName(),
                t.getLei(),
                t.getNcaName(),
                t.getNcaEmail(),
                t.getJurisdictionIso(),
                t.getPrimaryComplianceContactId()
        );
    }
}
