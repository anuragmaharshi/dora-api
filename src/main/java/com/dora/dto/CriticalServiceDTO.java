package com.dora.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a critical service entry returned by the admin endpoints.
 */
public record CriticalServiceDTO(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        boolean active,
        Instant createdAt
) {}
