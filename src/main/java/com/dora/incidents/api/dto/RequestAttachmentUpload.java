package com.dora.incidents.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/incidents/{id}/attachments (LLD-05 §4, AC-3).
 *
 * <p>sizeBytes is validated on the server to enforce the per-file cap (50MB default,
 * configurable via INCIDENT_ATTACHMENT_MAX_MB). The client must provide the file size
 * upfront so the service can reject oversized files before a presigned URL is issued —
 * without this, the rejection would happen at the MinIO level with a less clear error.
 */
public record RequestAttachmentUpload(

        @NotBlank(message = "filename is required")
        @Size(max = 500, message = "filename must be ≤500 characters")
        String filename,

        @NotBlank(message = "contentType is required")
        @Size(max = 200, message = "contentType must be ≤200 characters")
        String contentType,

        @NotNull(message = "sizeBytes is required")
        @Min(value = 1, message = "sizeBytes must be > 0")
        Long sizeBytes

) {}
