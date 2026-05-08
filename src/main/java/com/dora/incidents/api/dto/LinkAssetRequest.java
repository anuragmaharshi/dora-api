package com.dora.incidents.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/incidents/{id}/assets (LLD-05 §4, AC-5).
 *
 * <p>Asset type is free-form (VARCHAR(100)) — the taxonomy is extensible and managed
 * at the application layer. The DB only enforces non-empty via CHECK constraint.
 */
public record LinkAssetRequest(

        @NotBlank(message = "asset name is required")
        @Size(max = 200, message = "asset name must be ≤200 characters")
        String name,

        @NotBlank(message = "asset type is required")
        @Size(max = 100, message = "asset type must be ≤100 characters")
        String type

) {}
