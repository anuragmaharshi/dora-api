package com.dora.incidents.domain;

import com.dora.services.audit.AuditedRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link IctAsset} entities.
 *
 * <p>Extends {@link AuditedRepository} for hard-delete guard. ICT assets are
 * append-only — they are never updated or deleted once linked to an incident.
 */
@Repository
public interface IctAssetRepository extends AuditedRepository<IctAsset, UUID> {

    /** Returns all ICT assets linked to an incident. */
    List<IctAsset> findByIncidentId(UUID incidentId);
}
