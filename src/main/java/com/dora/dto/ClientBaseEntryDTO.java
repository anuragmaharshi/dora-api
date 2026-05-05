package com.dora.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single entry in the client base history.
 */
public record ClientBaseEntryDTO(
        UUID id,
        UUID tenantId,
        long clientCount,
        Instant effectiveFrom,
        UUID setBy,
        Instant createdAt
) {}
