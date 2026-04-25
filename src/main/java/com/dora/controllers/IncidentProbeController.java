package com.dora.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Probe endpoint for RBAC smoke tests (LLD-02 §7 RbacIntegrationTest).
 *
 * Purpose: verify that BR-011 / NFR-009 are enforced at the method-security layer —
 * PLATFORM_ADMIN must receive 403 on any incident-data path. This stub stands in
 * for the real incident endpoints until LLD-03 arrives.
 *
 * @PreAuthorize permits every bank role explicitly. PLATFORM_ADMIN is excluded by
 * omission — the expression cannot use a "deny" list, so all permitted roles are listed.
 * SYSTEM is also excluded: it is for internal/automated use only (RoleNames.SYSTEM).
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentProbeController {

    @GetMapping("/_probe")
    @PreAuthorize("hasAnyRole('OPS_ANALYST','INCIDENT_MANAGER','COMPLIANCE_OFFICER','CISO','BOARD_VIEWER')")
    public ResponseEntity<Map<String, String>> probe() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
