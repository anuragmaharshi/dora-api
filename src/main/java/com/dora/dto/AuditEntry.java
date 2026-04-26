package com.dora.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side projection of an {@code audit_log} row returned by {@code GET /api/v1/audit}
 * (LLD-03 §4, FR-029).
 *
 * <p>Java record: immutable, no-boilerplate, aligns with the OpenAPI {@code AuditEntry}
 * schema in {@code openapi.yaml}. All fields are nullable at the protocol level because
 * {@code actor_id} and {@code entity_id} are nullable in the source table.
 *
 * <p>Field notes:
 * <ul>
 *   <li>{@code actorId} — NULL for SYSTEM actions.</li>
 *   <li>{@code entityId} — NULL for non-entity events (e.g. DEADLINE_ALERT_SENT).</li>
 *   <li>{@code beforeState} / {@code afterState} — NULL on CREATE / SYSTEM events respectively.</li>
 *   <li>{@code context} — the {@code {request_id, remote_ip, user_agent}} forensic object (Q-2).</li>
 * </ul>
 */
public record AuditEntry(
        UUID id,
        UUID tenantId,
        UUID actorId,
        String actorUsername,
        String action,
        String entityType,
        UUID entityId,
        JsonNode beforeState,
        JsonNode afterState,
        JsonNode context,
        Instant createdAt
) {
}
