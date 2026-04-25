package com.dora.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the {@code tenant} table created in V1_1_0__auth_tables.sql.
 * Soft-delete only — tenants are never hard-deleted (LLD-02 §5 retention rules).
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "legal_name", nullable = false, unique = true, length = 255)
    private String legalName;

    @Column(name = "lei", unique = true, length = 20)
    private String lei;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Tenant() {
        // JPA requires a no-arg constructor; package-private to discourage direct use.
    }

    public Tenant(String legalName, String lei) {
        this.legalName = legalName;
        this.lei = lei;
    }

    public UUID getId() {
        return id;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getLei() {
        return lei;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // equals/hashCode on surrogate PK only — standard JPA practice
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tenant other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Tenant{id=" + id + ", legalName='" + legalName + "'}";
    }
}
