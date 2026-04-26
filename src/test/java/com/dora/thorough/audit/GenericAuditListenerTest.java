package com.dora.thorough.audit;

import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import com.dora.services.audit.AuditServiceHolder;
import com.dora.services.audit.AuditedEntity;
import com.dora.services.audit.GenericAuditListener;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Thorough unit tests for {@link GenericAuditListener}.
 *
 * <p>No Spring context — uses {@link AuditServiceHolder#set} to inject mocks.
 * Static holder is restored to null in {@code @AfterEach} for test isolation.
 */
@Tag("AC-1")
@DisplayName("GenericAuditListener — thorough unit tests")
class GenericAuditListenerTest {

    // ── Test entity types ─────────────────────────────────────────────────────

    /** Entity WITH @AuditedEntity — listener should fire. */
    @AuditedEntity
    private static class AuditedProbe {
        private final UUID id = UUID.randomUUID();
        private final String name = "probe";
        private final int count = 42;

        public UUID getId() { return id; }
        public String getName() { return name; }
        public int getCount() { return count; }
    }

    /** Entity WITHOUT @AuditedEntity — listener must skip. */
    private static class NonAuditedProbe {
        private final UUID id = UUID.randomUUID();
        public UUID getId() { return id; }
    }

    /** Entity with no getId() method — listener should not throw but entity_id will be null. */
    @AuditedEntity
    private static class NoIdEntity {
        public String getName() { return "no-id"; }
    }

    /** Entity where getId() returns a non-UUID type. */
    @AuditedEntity
    private static class LongIdEntity {
        public Long getId() { return 99L; }
    }

    private AuditService mockService;
    private GenericAuditListener listener;

    @BeforeEach
    void setUp() {
        mockService = Mockito.mock(AuditService.class);
        AuditServiceHolder.set(mockService);
        listener = new GenericAuditListener();
    }

    @AfterEach
    void tearDown() {
        AuditServiceHolder.set(null);
    }

    // ── prePersist ────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() on entity WITHOUT @AuditedEntity does NOT call AuditService")
    void prePersist_nonAuditedEntity_neverCallsRecord() {
        NonAuditedProbe entity = new NonAuditedProbe();

        listener.prePersist(entity);

        verify(mockService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() on entity WITH @AuditedEntity calls AuditService.record with before=null")
    void prePersist_auditedEntity_callsRecordWithNullBefore() {
        AuditedProbe entity = new AuditedProbe();

        listener.prePersist(entity);

        ArgumentCaptor<JsonNode> afterCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(mockService, times(1)).record(
                eq(AuditAction.SYSTEM),
                eq("AUDITEDPROBE"),
                eq(entity.getId()),
                isNull(),          // before must be null on prePersist
                afterCaptor.capture()
        );

        // after must be non-null and contain the entity's fields
        JsonNode after = afterCaptor.getValue();
        assertThat(after).isNotNull();
        assertThat(after.has("name")).isTrue();
        assertThat(after.get("name").asText()).isEqualTo("probe");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() calls record with entityType = simple class name in UPPER_CASE")
    void prePersist_entityType_isSimpleClassNameUpperCase() {
        AuditedProbe entity = new AuditedProbe();

        listener.prePersist(entity);

        verify(mockService).record(
                eq(AuditAction.SYSTEM),
                eq("AUDITEDPROBE"),  // SimpleClassName.toUpperCase()
                any(),
                isNull(),
                any()
        );
    }

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() when AuditServiceHolder returns null — no exception, just WARN log")
    void prePersist_nullAuditService_doesNotThrow() {
        AuditServiceHolder.set(null);
        AuditedProbe entity = new AuditedProbe();

        assertThatCode(() -> listener.prePersist(entity))
                .doesNotThrowAnyException();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() when AuditServiceHolder returns null — AuditService is never called")
    void prePersist_nullAuditService_neverCallsRecord() {
        AuditServiceHolder.set(null);
        AuditedProbe entity = new AuditedProbe();

        listener.prePersist(entity);

        // mockService was set to null in holder; no calls should happen on the old mock
        verify(mockService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() on entity with no getId() method does not throw — entityId is null")
    void prePersist_entityWithNoGetId_doesNotThrow() {
        NoIdEntity entity = new NoIdEntity();

        assertThatCode(() -> listener.prePersist(entity))
                .doesNotThrowAnyException();

        verify(mockService).record(
                eq(AuditAction.SYSTEM),
                any(String.class),
                isNull(),  // entityId null because no UUID getId()
                isNull(),
                any()
        );
    }

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() on entity where getId() returns non-UUID uses null entityId")
    void prePersist_entityWithLongId_usesNullEntityId() {
        LongIdEntity entity = new LongIdEntity();

        assertThatCode(() -> listener.prePersist(entity))
                .doesNotThrowAnyException();

        verify(mockService).record(
                eq(AuditAction.SYSTEM),
                any(String.class),
                isNull(),
                isNull(),
                any()
        );
    }

    // ── preUpdate ─────────────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("preUpdate() on entity WITHOUT @AuditedEntity does NOT call AuditService")
    void preUpdate_nonAuditedEntity_neverCallsRecord() {
        NonAuditedProbe entity = new NonAuditedProbe();

        listener.preUpdate(entity);

        verify(mockService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("preUpdate() on entity WITH @AuditedEntity calls AuditService.record with before=null and after=non-null snapshot")
    void preUpdate_auditedEntity_callsRecordWithNullBeforeAndNonNullAfter() {
        AuditedProbe entity = new AuditedProbe();

        listener.preUpdate(entity);

        ArgumentCaptor<JsonNode> afterCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(mockService, times(1)).record(
                eq(AuditAction.SYSTEM),
                eq("AUDITEDPROBE"),
                eq(entity.getId()),
                isNull(),     // before is null in GenericAuditListener.preUpdate (by design)
                afterCaptor.capture()
        );

        assertThat(afterCaptor.getValue()).isNotNull();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("preUpdate() when AuditServiceHolder returns null — no exception thrown")
    void preUpdate_nullAuditService_doesNotThrow() {
        AuditServiceHolder.set(null);
        AuditedProbe entity = new AuditedProbe();

        assertThatCode(() -> listener.preUpdate(entity))
                .doesNotThrowAnyException();
    }

    @Test
    @Tag("AC-1")
    @DisplayName("prePersist() after set/clear/set cycle sees fresh mock (holder is truly volatile)")
    void holderSetClearSet_isReflectedInListener() {
        // Clear
        AuditServiceHolder.set(null);
        AuditedProbe entity = new AuditedProbe();
        listener.prePersist(entity);
        verify(mockService, never()).record(any(), any(), any(), any(), any());

        // Re-set with a new mock
        AuditService newMock = Mockito.mock(AuditService.class);
        AuditServiceHolder.set(newMock);
        listener.prePersist(entity);
        verify(newMock, times(1)).record(any(), any(), any(), any(), any());

        // Restore original for tearDown
        AuditServiceHolder.set(mockService);
    }
}
