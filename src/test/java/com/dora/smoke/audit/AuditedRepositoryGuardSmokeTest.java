package com.dora.smoke.audit;

import com.dora.services.audit.AuditedRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-6 smoke test: AuditedRepository.delete* methods throw UnsupportedOperationException.
 *
 * <p>Given any JPA entity decorated with @AuditedEntity,
 * When the application layer attempts repository.delete(...),
 * Then the call is rejected at runtime (AC-6, NFR-005).
 *
 * <p>Pure unit test — no Spring context required. Uses Mockito.mock() with
 * CALLS_REAL_METHODS so the default method overrides in AuditedRepository are invoked.
 */
@Tag("AC-6")
@DisplayName("AC-6: AuditedRepository.delete* methods throw UnsupportedOperationException")
class AuditedRepositoryGuardSmokeTest {

    /** Minimal stub entity for the generic type parameter. */
    private static class StubEntity {}

    private static final String EXPECTED_MESSAGE =
            "Hard delete is prohibited — use archive (NFR-005).";

    // Mockito CALLS_REAL_METHODS so the default method overrides execute.
    @SuppressWarnings("unchecked")
    private static AuditedRepository<StubEntity, UUID> repo() {
        return Mockito.mock(AuditedRepository.class, Mockito.CALLS_REAL_METHODS);
    }

    @Test
    @DisplayName("delete(entity) throws UnsupportedOperationException with expected message")
    void delete_throws() {
        assertThatThrownBy(() -> repo().delete(new StubEntity()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @DisplayName("deleteById(id) throws UnsupportedOperationException with expected message")
    void deleteById_throws() {
        assertThatThrownBy(() -> repo().deleteById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @DisplayName("deleteAll(Iterable) throws UnsupportedOperationException with expected message")
    void deleteAllIterable_throws() {
        assertThatThrownBy(() -> repo().deleteAll(List.of(new StubEntity())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @DisplayName("deleteAllInBatch() throws UnsupportedOperationException with expected message")
    void deleteAllInBatch_throws() {
        assertThatThrownBy(() -> repo().deleteAllInBatch())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }
}
