package com.dora.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Maps {@code app_role}.
 *
 * Natural PK: the role {@code code} is stable (e.g. "PLATFORM_ADMIN") and used
 * directly in JWT {@code roles} claims and {@code @PreAuthorize} expressions.
 * A surrogate UUID would add no value here — this is intentional per LLD-02 §5.
 */
@Entity
@Table(name = "app_role")
public class AppRole {

    @Id
    @Column(name = "code", length = 50, updatable = false, nullable = false)
    private String code;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    protected AppRole() {
        // JPA no-arg
    }

    public AppRole(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    // Natural PK — equals/hashCode on code directly
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppRole other)) return false;
        return Objects.equals(code, other.code);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }

    @Override
    public String toString() {
        return "AppRole{code='" + code + "'}";
    }
}
