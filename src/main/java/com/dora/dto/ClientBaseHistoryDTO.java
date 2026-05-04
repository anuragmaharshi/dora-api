package com.dora.dto;

import java.util.List;

/**
 * Response for GET /api/v1/admin/client-base — the full append-only history, newest first.
 */
public record ClientBaseHistoryDTO(
        List<ClientBaseEntryDTO> entries
) {}
