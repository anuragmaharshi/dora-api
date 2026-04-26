package com.dora.smoke.audit;

import com.dora.services.AuditService;
import com.dora.services.audit.AuditAction;
import com.dora.services.audit.AuditServiceHolder;
import com.dora.services.audit.AuditedEntity;
import com.dora.services.audit.GenericAuditListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * AC-1 (partial): GenericAuditListener calls AuditService.record() on @PrePersist.
 *
 * <p>Pure unit test — no Spring context, no DB. The static holder is set with a Mockito spy
 * and cleared after each test. This verifies the listener wiring without requiring a full
 * Testcontainers PostgreSQL setup.
 */
@Tag("AC-1")
@DisplayName("AC-1 (partial): GenericAuditListener fires on @PrePersist and calls AuditService")
class GenericAuditListenerSmokeTest {

    /** Minimal probe entity annotated with @AuditedEntity. */
    @AuditedEntity
    private static class ProbeEntity {
        private final UUID id = UUID.randomUUID();
        private final String name = "probe";

        public UUID getId() { return id; }
        public String getName() { return name; }
    }

    /** Entity WITHOUT @AuditedEntity — listener must ignore it. */
    private static class NonAuditedEntity {
        private final UUID id = UUID.randomUUID();
        public UUID getId() { return id; }
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
        // Clear the static holder so other tests start clean.
        AuditServiceHolder.set(null);
    }

    @Test
    @DisplayName("prePersist on @AuditedEntity calls AuditService.record with null before")
    void prePersist_auditedEntity_callsRecord() {
        ProbeEntity entity = new ProbeEntity();

        listener.prePersist(entity);

        // before is null (creation), after is non-null (entity serialised)
        verify(mockService, times(1)).record(
                eq(AuditAction.SYSTEM),
                eq("PROBEENTITY"),
                eq(entity.getId()),
                eq(null),     // before — null on creation
                any()         // after — Jackson snapshot
        );
    }

    @Test
    @DisplayName("prePersist on non-@AuditedEntity does NOT call AuditService")
    void prePersist_nonAuditedEntity_doesNotCallRecord() {
        NonAuditedEntity entity = new NonAuditedEntity();

        listener.prePersist(entity);

        Mockito.verifyNoInteractions(mockService);
    }

    @Test
    @DisplayName("preUpdate on @AuditedEntity calls AuditService.record")
    void preUpdate_auditedEntity_callsRecord() {
        ProbeEntity entity = new ProbeEntity();

        listener.preUpdate(entity);

        verify(mockService, times(1)).record(
                eq(AuditAction.SYSTEM),
                eq("PROBEENTITY"),
                eq(entity.getId()),
                eq(null),
                any()
        );
    }

    @Test
    @DisplayName("prePersist with null AuditService (holder not yet set) does not throw")
    void prePersist_nullService_doesNotThrow() {
        AuditServiceHolder.set(null);
        ProbeEntity entity = new ProbeEntity();

        // Must not throw — listener logs a WARN and returns gracefully.
        org.assertj.core.api.Assertions.assertThatCode(() -> listener.prePersist(entity))
                .doesNotThrowAnyException();
    }
}
