package com.dora.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for PUT /api/v1/admin/tenant.
 *
 * <p>legalName is required. All other fields are optional — sending null leaves the
 * existing value unchanged in the caller's responsibility; the service applies partial
 * update semantics (non-null values overwrite, null values clear the field).
 */
public record TenantConfigUpdateDTO(
        @NotBlank String legalName,

        /**
         * LEI (Legal Entity Identifier) — 20 uppercase alphanumeric chars per ISO 17442.
         * Validation is only applied when the field is non-null.
         */
        @Pattern(
            regexp = "^[A-Z0-9]{20}$",
            message = "LEI must be exactly 20 uppercase alphanumeric characters"
        )
        String lei,

        String ncaName,

        @Email(message = "NCA email must be a valid email address")
        String ncaEmail,

        @Size(min = 2, max = 2, message = "jurisdictionIso must be a 2-character ISO 3166-1 alpha-2 code")
        String jurisdictionIso,

        UUID primaryComplianceContactId
) {}
