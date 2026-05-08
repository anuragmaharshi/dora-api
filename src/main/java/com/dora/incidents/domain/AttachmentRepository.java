package com.dora.incidents.domain;

import com.dora.services.audit.AuditedRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Attachment} entities.
 *
 * <p>Extends {@link AuditedRepository} to inherit the hard-delete guard (NFR-005 / LLD-03).
 * The migration also REVOKEs DELETE ON attachment FROM dora for DB-level enforcement.
 */
@Repository
public interface AttachmentRepository extends AuditedRepository<Attachment, UUID> {

    /** Returns all attachments for an incident, ordered by creation time. */
    List<Attachment> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);

    /** Used by completeAttachment to load the attachment with its parent incident reference. */
    Optional<Attachment> findByIdAndIncidentId(UUID id, UUID incidentId);
}
