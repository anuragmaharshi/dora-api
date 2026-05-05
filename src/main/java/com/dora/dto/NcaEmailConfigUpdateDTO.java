package com.dora.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for PUT /api/v1/admin/nca-email.
 */
public record NcaEmailConfigUpdateDTO(
        @NotBlank @Email String sender,
        @NotBlank @Email String recipient,
        @NotBlank @Size(max = 500) String subjectTemplate
) {}
