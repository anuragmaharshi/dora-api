package com.dora.services;

import com.dora.dto.ClientBaseEntryDTO;
import com.dora.dto.ClientBaseHistoryDTO;
import com.dora.dto.SetClientBaseDTO;
import com.dora.entities.ClientBaseEntry;
import com.dora.repositories.ClientBaseEntryRepository;
import com.dora.services.audit.AuditAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the Client Base admin screen (LLD-04 §4, AC-3, AC-8).
 *
 * <p>This table is append-only (D-LLD04-1): each call to {@link #addEntry} inserts a new row;
 * it never modifies existing rows. The history is retained so that LLD-07 can use the
 * count that was in effect at the time of an incident.
 *
 * <p>{@link #countAsOf(UUID, Instant)} is the denominator query consumed by LLD-07.
 */
@Service
@Transactional
public class ClientBaseService {

    private final ClientBaseEntryRepository repository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ClientBaseService(ClientBaseEntryRepository repository,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the full history of client base entries for a tenant, most recent first.
     */
    @Transactional(readOnly = true)
    public ClientBaseHistoryDTO getHistory(UUID tenantId) {
        List<ClientBaseEntryDTO> entries = repository
                .findByTenantIdOrderByEffectiveFromDesc(tenantId)
                .stream()
                .map(this::toDTO)
                .toList();
        return new ClientBaseHistoryDTO(entries);
    }

    /**
     * Appends a new client base count entry.
     *
     * @param tenantId tenant being updated
     * @param setBy    UUID of the authenticated PLATFORM_ADMIN performing the action
     * @param dto      the count and effective date
     * @return the newly created entry
     */
    public ClientBaseEntryDTO addEntry(UUID tenantId, UUID setBy, SetClientBaseDTO dto) {
        ClientBaseEntry entry = new ClientBaseEntry(
                tenantId,
                dto.clientCount(),
                dto.effectiveFrom(),
                setBy
        );
        ClientBaseEntry saved = repository.save(entry);

        auditService.record(
                AuditAction.CLIENT_BASE_ENTRY_ADDED,
                "CLIENT_BASE_ENTRY",
                saved.getId(),
                null,
                objectMapper.valueToTree(toDTO(saved))
        );

        return toDTO(saved);
    }

    /**
     * Returns the client count in effect at {@code asOf} for LLD-07 C1 threshold calculation.
     *
     * <p>Returns {@link Optional#empty()} if no entry exists with effective_from ≤ asOf,
     * which the caller (LLD-07) must handle as "unconfigured" (treat as 0 or skip C1 check).
     *
     * @param tenantId the tenant
     * @param asOf     the point in time (usually the incident's occurrence time)
     * @return the count in effect at that time, or empty
     */
    @Transactional(readOnly = true)
    public Optional<Long> countAsOf(UUID tenantId, Instant asOf) {
        return repository
                .findTopByTenantIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(tenantId, asOf)
                .map(ClientBaseEntry::getClientCount);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private ClientBaseEntryDTO toDTO(ClientBaseEntry e) {
        return new ClientBaseEntryDTO(
                e.getId(),
                e.getTenantId(),
                e.getClientCount(),
                e.getEffectiveFrom(),
                e.getSetBy(),
                e.getCreatedAt()
        );
    }
}
