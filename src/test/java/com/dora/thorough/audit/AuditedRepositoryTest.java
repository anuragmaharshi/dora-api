package com.dora.thorough.audit;

import com.dora.services.audit.AuditedRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Thorough unit tests for {@link AuditedRepository} default delete guard methods.
 *
 * <p>Pure unit test — no Spring context required. Tests the four prohibited operations
 * and verifies the exact message text, which appears in exception logs and incident
 * response procedures (NFR-005).
 *
 * <p>Two approaches to exercise the default methods:
 * <ol>
 *   <li>{@code Mockito.mock(AuditedRepository.class, CALLS_REAL_METHODS)} — exactly as
 *       the smoke test uses, verifying the base smoke approach still works.</li>
 *   <li>A minimal concrete anonymous inner class — exercises the interface directly,
 *       independent of Mockito behaviour around default methods.</li>
 * </ol>
 */
@Tag("AC-6")
@DisplayName("AuditedRepository — thorough guard tests")
class AuditedRepositoryTest {

    private static final String EXPECTED_MESSAGE =
            "Hard delete is prohibited — use archive (NFR-005).";

    /** Minimal stub entity. */
    private static class Widget {
        private final UUID id;
        Widget(UUID id) { this.id = id; }
        public UUID getId() { return id; }
    }

    /**
     * Concrete minimal implementation of AuditedRepository for testing default methods.
     * All non-default methods throw UnsupportedOperationException — the tests only
     * call the four guarded default methods.
     */
    private static class ConcreteWidgetRepository implements AuditedRepository<Widget, UUID> {
        @Override public <S extends Widget> S save(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<Widget> findById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public List<Widget> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<Widget> findAllById(Iterable<UUID> uuids) { throw new UnsupportedOperationException(); }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends UUID> uuids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public List<Widget> findAll(Sort sort) { throw new UnsupportedOperationException(); }
        @Override public Page<Widget> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public void flush() { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> S saveAndFlush(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<Widget> entities) { throw new UnsupportedOperationException(); }
        @Override public Widget getReferenceById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public Widget getById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public Widget getOne(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> Optional<S> findOne(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> List<S> findAll(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> List<S> findAll(Example<S> example, Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> Page<S> findAll(Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> long count(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget> boolean exists(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends Widget, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }

    // ── Mockito CALLS_REAL_METHODS variant (matches smoke approach) ───────────

    @SuppressWarnings("unchecked")
    private static AuditedRepository<Widget, UUID> mockRepo() {
        return Mockito.mock(AuditedRepository.class, Mockito.CALLS_REAL_METHODS);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("delete(entity) throws UnsupportedOperationException with exact message")
    void delete_throws_exactMessage() {
        assertThatThrownBy(() -> mockRepo().delete(new Widget(UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("deleteById(id) throws UnsupportedOperationException with exact message")
    void deleteById_throws_exactMessage() {
        assertThatThrownBy(() -> mockRepo().deleteById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("deleteAll(Iterable) throws UnsupportedOperationException with exact message")
    void deleteAllIterable_throws_exactMessage() {
        assertThatThrownBy(() -> mockRepo().deleteAll(List.of(new Widget(UUID.randomUUID()))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("deleteAllInBatch() throws UnsupportedOperationException with exact message")
    void deleteAllInBatch_throws_exactMessage() {
        assertThatThrownBy(() -> mockRepo().deleteAllInBatch())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    // ── Concrete implementation variant (no Mockito) ─────────────────────────

    @Test
    @Tag("AC-6")
    @DisplayName("delete(entity) on concrete impl throws UnsupportedOperationException (no Mockito)")
    void delete_concreteImpl_throws() {
        ConcreteWidgetRepository repo = new ConcreteWidgetRepository();
        assertThatThrownBy(() -> repo.delete(new Widget(UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("deleteById(id) on concrete impl throws UnsupportedOperationException (no Mockito)")
    void deleteById_concreteImpl_throws() {
        ConcreteWidgetRepository repo = new ConcreteWidgetRepository();
        assertThatThrownBy(() -> repo.deleteById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("deleteAll(Iterable) on concrete impl throws UnsupportedOperationException (no Mockito)")
    void deleteAllIterable_concreteImpl_throws() {
        ConcreteWidgetRepository repo = new ConcreteWidgetRepository();
        assertThatThrownBy(() -> repo.deleteAll(List.of(new Widget(UUID.randomUUID()))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    @Test
    @Tag("AC-6")
    @DisplayName("deleteAllInBatch() on concrete impl throws UnsupportedOperationException (no Mockito)")
    void deleteAllInBatch_concreteImpl_throws() {
        ConcreteWidgetRepository repo = new ConcreteWidgetRepository();
        assertThatThrownBy(() -> repo.deleteAllInBatch())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXPECTED_MESSAGE);
    }

    // ── Message content checks ────────────────────────────────────────────────

    @Test
    @Tag("AC-6")
    @DisplayName("delete() message contains 'Hard delete is prohibited' substring")
    void delete_messageContainsHardDeleteSubstring() {
        assertThatThrownBy(() -> mockRepo().delete(new Widget(UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Hard delete is prohibited");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("delete() message contains 'NFR-005' requirement reference")
    void delete_messageContainsNfrReference() {
        assertThatThrownBy(() -> mockRepo().delete(new Widget(UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("NFR-005");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("deleteById() message contains 'archive' instruction")
    void deleteById_messageContainsArchiveInstruction() {
        assertThatThrownBy(() -> mockRepo().deleteById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("archive");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("All four methods throw identical message text (no per-method variation)")
    void allFourMethods_throwIdenticalMessage() {
        Widget widget = new Widget(UUID.randomUUID());
        ConcreteWidgetRepository repo = new ConcreteWidgetRepository();

        String deleteMsg = getExceptionMessage(() -> repo.delete(widget));
        String deleteByIdMsg = getExceptionMessage(() -> repo.deleteById(widget.getId()));
        String deleteAllIterableMsg = getExceptionMessage(() -> repo.deleteAll(List.of(widget)));
        String deleteAllInBatchMsg = getExceptionMessage(() -> repo.deleteAllInBatch());

        assertThat(deleteMsg)
                .isEqualTo(deleteByIdMsg)
                .isEqualTo(deleteAllIterableMsg)
                .isEqualTo(deleteAllInBatchMsg)
                .isEqualTo(EXPECTED_MESSAGE);
    }

    private String getExceptionMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected UnsupportedOperationException but none was thrown");
        } catch (UnsupportedOperationException e) {
            return e.getMessage();
        }
    }
}
