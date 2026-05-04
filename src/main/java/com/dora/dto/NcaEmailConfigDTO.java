package com.dora.dto;

/**
 * Read view of the NCA email configuration.
 */
public record NcaEmailConfigDTO(
        String sender,
        String recipient,
        String subjectTemplate
) {}
