package com.dora.incidents.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for PUT /api/v1/incidents/{id} (LLD-05 §4, AC-2 update path).
 *
 * <p>Only the mutable fields of an incident may be updated:
 * title, description, and impactEstimate. The following fields are intentionally
 * absent because they are immutable:
 * <ul>
 *   <li>{@code detection_datetime} — immutable per FR-002 / D-LLD05-1; JPA {@code updatable=false}
 *       + DB trigger both prevent any change. AC-2 explicitly requires 422 on any attempt.</li>
 *   <li>{@code incident_id} — human-readable ID is assigned once at creation.</li>
 *   <li>{@code tenant_id}, {@code created_by} — ownership / provenance, never client-mutable.</li>
 *   <li>{@code status} — lifecycle state machine is LLD-06 scope; not part of this endpoint.</li>
 * </ul>
 *
 * <p>OPEN-Q: LLD-05 §4 authz table does not define PUT /incidents/{id} at all.
 * Authorization assumed to be hasRole('INCIDENT_MANAGER') per dispatch instructions.
 * Pending BA confirmation — see agent-state/LLD-05/STATE.md open_questions.
 */
public record UpdateIncidentRequest(

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be ≤200 characters")
        String title,

        @NotBlank(message = "description is required")
        String description,

        // nullable — estimate may not be known or may not change
        String impactEstimate

) {}
