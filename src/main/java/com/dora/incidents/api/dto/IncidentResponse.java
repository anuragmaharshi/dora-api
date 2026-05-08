package com.dora.incidents.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full incident detail returned by GET /api/v1/incidents/{id} (LLD-05 §4, AC-6).
 *
 * <p>Includes the complete set of linked entities: attachments, linked services,
 * and linked ICT assets. Computed by IncidentService.findById() within a single
 * read transaction to avoid LazyInitializationException.
 */
public record IncidentResponse(

        UUID id,
        String incidentId,            // INC-YYYYMMDD-NNNN human-readable
        String title,
        String description,
        String impactEstimate,
        Instant detectionDatetime,    // server-stamped, never client-supplied
        String status,
        UUID tenantId,
        UUID createdBy,
        Instant createdAt,
        List<AttachmentResponse> attachments,
        List<LinkedServiceResponse> services,
        List<IctAssetResponse> assets

) {

    /**
     * Nested DTO for a linked critical service — only the fields needed for display.
     */
    public record LinkedServiceResponse(UUID serviceId, String name) {}
}
