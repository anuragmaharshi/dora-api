package com.dora.incidents.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for POST /api/v1/incidents/{id}/attachments (LLD-05 §4, AC-3).
 *
 * <p>The client receives this, then PUTs the file directly to {@code uploadUrl}
 * (against MinIO locally, real S3 in production). After the upload completes, the
 * client calls POST /attachments/{attachmentId}/complete to confirm delivery and
 * flip the attachment status to READY.
 *
 * <p>TTL is fixed at 15 minutes (D-LLD05-2). expiresAt surfaces this to the client
 * so it can warn the user if the page sits idle too long.
 */
public record PresignedUploadResponse(
        UUID attachmentId,
        String uploadUrl,
        Instant expiresAt
) {}
