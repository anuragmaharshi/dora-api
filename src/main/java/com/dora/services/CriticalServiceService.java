package com.dora.services;

import com.dora.dto.CreateCriticalServiceDTO;
import com.dora.dto.CriticalServiceDTO;
import com.dora.dto.UpdateCriticalServiceDTO;
import com.dora.entities.CriticalService;
import com.dora.repositories.CriticalServiceRepository;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Business logic for the Critical Services admin screen (LLD-04 §4, AC-2, AC-8).
 *
 * <p>Services are archived rather than deleted (D-LLD04-2 / NFR-005). All mutations
 * are audited within the caller's transaction.
 *
 * <p>The {@link #listActive(UUID)} method is the canonical picklist for LLD-05 incident creation.
 */
@Service
@Transactional
public class CriticalServiceService {

    private final CriticalServiceRepository repository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CriticalServiceService(CriticalServiceRepository repository,
                                   AuditService auditService,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns all services (active + archived) for the tenant.
     * The admin UI uses this to show the full list with status badges.
     */
    @Transactional(readOnly = true)
    public List<CriticalServiceDTO> list(UUID tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Returns only active services — the picklist for LLD-05.
     */
    @Transactional(readOnly = true)
    public List<CriticalServiceDTO> listActive(UUID tenantId) {
        return repository.findByTenantIdAndActive(tenantId, true).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Creates a new critical service for the tenant.
     *
     * @throws ResponseStatusException 409 if a service with the same name already exists for this tenant
     */
    public CriticalServiceDTO create(UUID tenantId, CreateCriticalServiceDTO dto) {
        CriticalService entity = new CriticalService(tenantId, dto.name(), dto.description());
        CriticalService saved;
        try {
            saved = repository.save(entity);
            repository.flush(); // flush to trigger DB unique constraint before leaving this method
        } catch (DataIntegrityViolationException ex) {
            // OPEN-Q: duplicate name — assumed 409 per fintech convention (conflict, not validation)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A critical service with name '" + dto.name() + "' already exists for this tenant");
        }

        auditService.record(
                AuditAction.CRITICAL_SERVICE_CREATED,
                "CRITICAL_SERVICE",
                saved.getId(),
                null,
                objectMapper.valueToTree(toDTO(saved))
        );

        return toDTO(saved);
    }

    /**
     * Renames or updates the description of an existing service.
     *
     * @throws ResponseStatusException 404 if the service is not found for this tenant
     * @throws ResponseStatusException 409 if a service with the new name already exists
     */
    public CriticalServiceDTO update(UUID tenantId, UUID id, UpdateCriticalServiceDTO dto) {
        CriticalService entity = loadForTenant(tenantId, id);
        JsonNode before = objectMapper.valueToTree(toDTO(entity));

        entity.setName(dto.name());
        entity.setDescription(dto.description());

        CriticalService saved;
        try {
            saved = repository.save(entity);
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A critical service with name '" + dto.name() + "' already exists for this tenant");
        }

        auditService.record(
                AuditAction.CRITICAL_SERVICE_UPDATED,
                "CRITICAL_SERVICE",
                saved.getId(),
                before,
                objectMapper.valueToTree(toDTO(saved))
        );

        return toDTO(saved);
    }

    /**
     * Archives a critical service (sets active=false). Idempotent: archiving an already-archived
     * service is a no-op (no additional audit row).
     *
     * @throws ResponseStatusException 404 if the service is not found for this tenant
     */
    public void archive(UUID tenantId, UUID id) {
        CriticalService entity = loadForTenant(tenantId, id);
        if (entity.isActive()) {
            JsonNode before = objectMapper.valueToTree(toDTO(entity));
            entity.archive();
            CriticalService saved = repository.save(entity);
            auditService.record(
                    AuditAction.CRITICAL_SERVICE_ARCHIVED,
                    "CRITICAL_SERVICE",
                    saved.getId(),
                    before,
                    objectMapper.valueToTree(toDTO(saved))
            );
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private CriticalService loadForTenant(UUID tenantId, UUID id) {
        return repository.findById(id)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Critical service not found: " + id));
    }

    private CriticalServiceDTO toDTO(CriticalService s) {
        return new CriticalServiceDTO(
                s.getId(),
                s.getTenantId(),
                s.getName(),
                s.getDescription(),
                s.isActive(),
                s.getCreatedAt()
        );
    }
}
