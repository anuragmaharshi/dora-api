package com.dora.incidents.thorough.failures;

import com.dora.entities.CriticalService;
import com.dora.incidents.api.dto.CreateIncidentRequest;
import com.dora.incidents.api.dto.IncidentResponse;
import com.dora.incidents.api.dto.LinkAssetRequest;
import com.dora.incidents.api.dto.LinkServicesRequest;
import com.dora.incidents.application.IncidentIdGenerator;
import com.dora.incidents.application.IncidentService;
import com.dora.incidents.application.ObjectStorageClient;
import com.dora.incidents.domain.Attachment;
import com.dora.incidents.domain.AttachmentRepository;
import com.dora.incidents.domain.IctAsset;
import com.dora.incidents.domain.IctAssetRepository;
import com.dora.incidents.domain.Incident;
import com.dora.incidents.domain.IncidentRepository;
import com.dora.repositories.CriticalServiceRepository;
import com.dora.services.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for IncidentService: no Spring context, no DB.
 *
 * These tests validate:
 * - AC-1: service sets detection_datetime server-side (not from request)
 * - AC-1: service calls idGenerator.next() exactly once per create
 * - AC-1: AuditService.record() is called with INCIDENT_CREATED after successful create
 * - AC-4: service throws 422 when service ID is not in the active picklist
 * - AC-4: service throws 422 when service ID belongs to another tenant (not in active list)
 * - AC-5: service throws 404 when incident not found for linkAsset
 * - AC-6: service throws 404 when incident not found for findById
 * - AC-6: service throws 404 (not 403) when incident belongs to different tenant (masked as 404)
 * - AC-3: service throws 404 when addAttachment called on non-existent incident
 * - AC-3: service throws 422 when sizeBytes exceeds maxAttachmentBytes
 */
@Tag("AC-1")
@DisplayName("IncidentService unit tests: failure modes and service-layer invariants")
@ExtendWith(MockitoExtension.class)
class IncidentServiceUnitTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private IctAssetRepository ictAssetRepository;

    @Mock
    private CriticalServiceRepository criticalServiceRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private IncidentIdGenerator idGenerator;

    @Mock
    private ObjectStorageClient storageClient;

    // Real ObjectMapper — no reason to mock it for service tests
    private final ObjectMapper objectMapper = new ObjectMapper();

    private IncidentService service;

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final int MAX_MB = 10;

    @BeforeEach
    void setUp() {
        service = new IncidentService(
                incidentRepository,
                attachmentRepository,
                ictAssetRepository,
                criticalServiceRepository,
                auditService,
                idGenerator,
                storageClient,
                objectMapper,
                MAX_MB
        );
    }

    // ── AC-1: detection_datetime is server-stamped ─────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 unit: create() stamps detectionDatetime server-side, not from any request field")
    void create_stampsDetectionDatetimeServerSide() {
        when(idGenerator.next()).thenReturn("INC-20260508-0001");
        // resolveActiveServices short-circuits on empty serviceIds — no criticalServiceRepository call needed

        long before = System.currentTimeMillis();
        CreateIncidentRequest request = new CreateIncidentRequest(
                "Test Incident", "Description", null, List.of(), List.of());
        IncidentResponse response = service.create(request, TENANT_A, ACTOR_ID);
        long after = System.currentTimeMillis();

        assertThat(response.detectionDatetime()).isNotNull();
        assertThat(response.detectionDatetime().toEpochMilli())
                .as("detectionDatetime must be within the test window")
                .isBetween(before, after);
    }

    // ── AC-1: idGenerator.next() called exactly once ───────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 unit: create() calls idGenerator.next() exactly once")
    void create_callsIdGeneratorExactlyOnce() {
        when(idGenerator.next()).thenReturn("INC-20260508-0001");
        // resolveActiveServices short-circuits on empty serviceIds

        CreateIncidentRequest request = new CreateIncidentRequest(
                "Test Incident", "Description", null, List.of(), List.of());
        service.create(request, TENANT_A, ACTOR_ID);

        verify(idGenerator).next();
    }

    // ── AC-1: AuditService.record called with INCIDENT_CREATED ────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 unit: create() calls AuditService.record() with INCIDENT_CREATED after persistence")
    void create_writesIncidentCreatedAuditEntry() {
        when(idGenerator.next()).thenReturn("INC-20260508-0001");
        // resolveActiveServices short-circuits on empty serviceIds

        CreateIncidentRequest request = new CreateIncidentRequest(
                "Audit Test Incident", "Description", null, List.of(), List.of());
        service.create(request, TENANT_A, ACTOR_ID);

        // AuditService must be called exactly once with INCIDENT_CREATED.
        // entityId is null without a real DB to assign the UUID via @GeneratedValue.
        verify(auditService).record(
                org.mockito.ArgumentMatchers.eq(com.dora.services.audit.AuditAction.INCIDENT_CREATED),
                org.mockito.ArgumentMatchers.eq("INCIDENT"),
                org.mockito.ArgumentMatchers.isNull(), // ID is null without DB UUID generation
                org.mockito.ArgumentMatchers.isNull(),
                any()
        );
    }

    // ── AC-4: inactive/unknown service ID → 422 ───────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 unit: create() with service ID not in active picklist throws 422")
    void create_withUnknownServiceId_throws422() {
        UUID unknownServiceId = UUID.randomUUID();
        // Active list is empty — no services for this tenant
        when(criticalServiceRepository.findByTenantIdAndActive(TENANT_A, true))
                .thenReturn(List.of());

        CreateIncidentRequest request = new CreateIncidentRequest(
                "Test", "Description", null, List.of(unknownServiceId), List.of());

        assertThatThrownBy(() -> service.create(request, TENANT_A, ACTOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // Nothing should be persisted
        verify(incidentRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    // ── AC-4: service from different tenant → 422 ─────────────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 unit: create() with service ID belonging to different tenant throws 422")
    void create_withServiceIdFromAnotherTenant_throws422() {
        UUID serviceBTenantId = UUID.randomUUID();

        // Tenant A has no active services; service with serviceBTenantId is in Tenant B
        when(criticalServiceRepository.findByTenantIdAndActive(TENANT_A, true))
                .thenReturn(List.of()); // empty — Tenant A has none

        CreateIncidentRequest request = new CreateIncidentRequest(
                "Test", "Description", null, List.of(serviceBTenantId), List.of());

        assertThatThrownBy(() -> service.create(request, TENANT_A, ACTOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── AC-5: linkAsset on non-existent incident → 404 ───────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 unit: linkAsset() with non-existent incident throws 404")
    void linkAsset_nonExistentIncident_throws404() {
        UUID unknownIncidentId = UUID.randomUUID();
        when(incidentRepository.findById(unknownIncidentId)).thenReturn(Optional.empty());

        LinkAssetRequest req = new LinkAssetRequest("Some Router", "NETWORK_DEVICE");

        assertThatThrownBy(() -> service.linkAsset(unknownIncidentId, req, TENANT_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── AC-6: findById with non-existent UUID → 404 ───────────────────────────

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 unit: findById() with non-existent UUID throws 404")
    void findById_nonExistentUUID_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(incidentRepository.findByIdWithDetails(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(unknownId, TENANT_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── AC-6: findById with wrong tenant → 404 (masked, not 403) ─────────────

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 unit: findById() with incident from different tenant throws 404 (not 403, for security masking)")
    void findById_incidentFromDifferentTenant_throws404NotForbidden() {
        UUID incidentId = UUID.randomUUID();

        // Incident belongs to TENANT_B
        Incident incidentFromB = buildMinimalIncident("INC-20260508-0099");
        // We need a way to set the tenantId — use reflection since there's no setter
        // (it's set in the constructor). Build it with TENANT_B.
        Incident tenantBIncident = new Incident(TENANT_B, "INC-20260508-0099",
                "Title", "Desc", null, ACTOR_ID);
        tenantBIncident.setDetectionDatetime(Instant.now());

        when(incidentRepository.findByIdWithDetails(incidentId))
                .thenReturn(Optional.of(tenantBIncident));

        // Called as TENANT_A — should get 404, not 403 (security masking per IncidentService)
        assertThatThrownBy(() -> service.findById(incidentId, TENANT_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── AC-3: addAttachment on non-existent incident → 404 ───────────────────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 unit: addAttachment() with non-existent incident throws 404")
    void addAttachment_nonExistentIncident_throws404() {
        UUID unknownIncidentId = UUID.randomUUID();
        when(incidentRepository.findById(unknownIncidentId)).thenReturn(Optional.empty());

        var req = new com.dora.incidents.api.dto.RequestAttachmentUpload(
                "file.pdf", "application/pdf", 1024L);

        assertThatThrownBy(() -> service.addAttachment(unknownIncidentId, req, TENANT_A, ACTOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── AC-3: addAttachment with file size > max → 422 before any DB call ─────

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3 unit: addAttachment() with oversized file throws 422 before any DB interaction")
    void addAttachment_oversizedFile_throws422BeforeAnyPersistence() {
        long oversized = (long) (MAX_MB + 1) * 1024 * 1024;

        var req = new com.dora.incidents.api.dto.RequestAttachmentUpload(
                "huge.zip", "application/zip", oversized);

        assertThatThrownBy(() -> service.addAttachment(UUID.randomUUID(), req, TENANT_A, ACTOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // Size check must happen before any DB query
        verify(incidentRepository, never()).findById(any());
        verify(attachmentRepository, never()).save(any());
    }

    // ── AC-4: linkServices on non-existent incident → 404 ─────────────────────

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 unit: linkServices() with non-existent incident throws 404")
    void linkServices_nonExistentIncident_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(incidentRepository.findByIdWithDetails(unknownId)).thenReturn(Optional.empty());

        LinkServicesRequest req = new LinkServicesRequest(List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.linkServices(unknownId, req, TENANT_A))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal Incident with the fields needed for service responses.
     * Uses the public constructor — no reflection.
     */
    private Incident buildMinimalIncident(String incidentId) {
        Incident incident = new Incident(TENANT_A, incidentId, "Test Incident",
                "Description", null, ACTOR_ID);
        incident.setDetectionDatetime(Instant.now());
        return incident;
    }
}
