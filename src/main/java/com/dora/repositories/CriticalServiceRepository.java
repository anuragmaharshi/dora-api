package com.dora.repositories;

import com.dora.entities.CriticalService;
import com.dora.services.audit.AuditedRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link CriticalService} entities.
 *
 * <p>Extends {@link AuditedRepository} to inherit the hard-delete guard (NFR-005 / LLD-03).
 * All archiving is done via {@link CriticalService#archive()} + save, never by delete.
 */
@Repository
public interface CriticalServiceRepository extends AuditedRepository<CriticalService, UUID> {

    /** Returns all services for a tenant, including archived. */
    List<CriticalService> findByTenantId(UUID tenantId);

    /** Returns only active (non-archived) services — the picklist consumed by LLD-05. */
    List<CriticalService> findByTenantIdAndActive(UUID tenantId, boolean active);
}
