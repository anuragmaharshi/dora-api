package com.dora.incidents.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Attachment metadata returned in incident detail and after /complete (LLD-05 §4, AC-3, AC-6).
 *
 * <p>s3Key is intentionally included — callers with read authorisation can use it
 * to request a presigned GET URL in a future endpoint (LLD-14 scope).
 */
public record AttachmentResponse(
        UUID id,
        UUID incidentId,
        String filename,
        String contentType,
        long sizeBytes,
        String s3Key,
        String status,           // PENDING | READY | FAILED
        UUID uploadedBy,
        Instant createdAt
) {}
