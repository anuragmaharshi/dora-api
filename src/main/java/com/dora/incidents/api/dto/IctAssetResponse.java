package com.dora.incidents.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * ICT asset linked to an incident (LLD-05 §4, AC-5, AC-6).
 */
public record IctAssetResponse(
        UUID id,
        UUID incidentId,
        String name,
        String type,
        Instant createdAt
) {}
