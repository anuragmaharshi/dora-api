package com.dora.services;

import com.dora.dto.NcaEmailConfigDTO;
import com.dora.dto.NcaEmailConfigUpdateDTO;
import com.dora.entities.NcaEmailConfig;
import com.dora.repositories.NcaEmailConfigRepository;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the NCA Email Configuration screen (LLD-04 §4, AC-4, AC-8).
 *
 * <p>At most one configuration exists per tenant (tenant_id is the PK). The first write
 * creates the row; subsequent writes update it. Both create and update are audited.
 */
@Service
@Transactional
public class NcaEmailService {

    private final NcaEmailConfigRepository repository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public NcaEmailService(NcaEmailConfigRepository repository,
                            AuditService auditService,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the current NCA email configuration for the tenant.
     * Returns an empty DTO (all nulls) if no configuration has been set yet.
     */
    @Transactional(readOnly = true)
    public NcaEmailConfigDTO getConfig(UUID tenantId) {
        return repository.findById(tenantId)
                .map(this::toDTO)
                .orElse(new NcaEmailConfigDTO(null, null, null));
    }

    /**
     * Creates or updates the NCA email configuration for the tenant.
     *
     * @param tenantId the tenant
     * @param dto      the new configuration values
     * @return the persisted configuration view
     */
    public NcaEmailConfigDTO updateConfig(UUID tenantId, NcaEmailConfigUpdateDTO dto) {
        Optional<NcaEmailConfig> existing = repository.findById(tenantId);

        JsonNode before = existing.map(c -> (JsonNode) objectMapper.valueToTree(toDTO(c)))
                .orElse(null);

        NcaEmailConfig config = existing.orElseGet(() -> new NcaEmailConfig(
                tenantId,
                dto.sender(),
                dto.recipient(),
                dto.subjectTemplate()
        ));

        if (existing.isPresent()) {
            config.setSender(dto.sender());
            config.setRecipient(dto.recipient());
            config.setSubjectTemplate(dto.subjectTemplate());
            config.touchUpdatedAt();
        }

        NcaEmailConfig saved = repository.save(config);
        JsonNode after = objectMapper.valueToTree(toDTO(saved));

        auditService.record(
                AuditAction.NCA_EMAIL_CONFIG_UPDATED,
                "NCA_EMAIL_CONFIG",
                tenantId,
                before,
                after
        );

        return toDTO(saved);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private NcaEmailConfigDTO toDTO(NcaEmailConfig c) {
        return new NcaEmailConfigDTO(c.getSender(), c.getRecipient(), c.getSubjectTemplate());
    }
}
