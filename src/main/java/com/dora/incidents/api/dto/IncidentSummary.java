package com.dora.incidents.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Compact projection returned by GET /api/v1/incidents (paginated list).
 *
 * <p>Excludes nested collections (attachments, assets, services) to keep the list
 * response light. Full detail is fetched via GET /api/v1/incidents/{id} (AC-6).
 * Rich search / filter is deferred to LLD-14.
 */
public record IncidentSummary(

        UUID id,
        String incidentId,
        String title,
        String status,
        Instant detectionDatetime,
        Instant createdAt,
        UUID tenantId,
        UUID createdBy

) {}
