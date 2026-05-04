package com.dora.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request body for POST /api/v1/admin/client-base.
 *
 * <p>Appends a new entry to the client base history. The effective date must be provided
 * by the caller (backfill scenarios are valid — the PLATFORM_ADMIN may record a historical count).
 */
public record SetClientBaseDTO(
        @NotNull @Min(0) Long clientCount,
        @NotNull Instant effectiveFrom
) {}
