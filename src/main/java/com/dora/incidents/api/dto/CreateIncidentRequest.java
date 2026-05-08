package com.dora.incidents.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/v1/incidents (LLD-05 §4, AC-1).
 *
 * <p>detection_datetime is intentionally absent — the server stamps it via
 * Instant.now() per FR-002 (immutability) and D-LLD05-1. A client providing a
 * "claimed detection" time should place it in the description.
 *
 * <p>serviceIds: only active entries from the critical_service picklist are
 * accepted; inactive IDs cause a 422 per AC-4.
 *
 * <p>affectedAssets: each entry becomes an ict_asset row directly linked to the
 * incident (no join table — BLOCKER-2 resolution, STATE.md).
 */
public record CreateIncidentRequest(

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be ≤200 characters")
        String title,

        @NotBlank(message = "description is required")
        String description,

        // nullable — estimate may not be known at detection time
        String impactEstimate,

        // may be empty; each ID must reference an active critical_service
        List<UUID> serviceIds,

        // may be empty; each entry becomes an ict_asset row
        // @Valid cascades nested constraints (e.g., @Size on AssetRequest.name) into the list elements;
        // without it, Bean Validation stops at the List boundary and oversized names return 500 (BUG-1).
        @Valid List<AssetRequest> assets

) {

    /**
     * Inline asset definition accepted on incident creation (AC-5).
     * Kept as a nested record to make the parent DTO self-describing.
     */
    public record AssetRequest(
            @NotBlank(message = "asset name is required")
            @Size(max = 200, message = "asset name must be ≤200 characters")
            String name,

            @NotBlank(message = "asset type is required")
            @Size(max = 100, message = "asset type must be ≤100 characters")
            String type
    ) {}
}
