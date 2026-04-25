package com.dora.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Maps the {@code app_user} table.
 *
 * Roles are fetched EAGER because every authenticated request needs them to build
 * the Spring Security {@code GrantedAuthority} list.  With STATELESS sessions there
 * is no second-level cache session to exploit, so the join at login/token-validation
 * time is the right trade-off.
 *
 * {@code mfa_enabled} is persisted and returned in {@link com.dora.dto.UserProfile};
 * enforcement is deferred to LLD-16 (Cognito).  See B-3 in dispatch notes.
 *
 * Soft-delete only — {@code active=false} instead of DELETE (LLD-02 §5).
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    // mfa_secret is nullable — TOTP seed reserved for LLD-16
    @Column(name = "mfa_secret", length = 255)
    private String mfaSecret;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // EAGER — needed on every authenticated request to populate GrantedAuthority list
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_role",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_code")
    )
    private Set<AppRole> roles = new HashSet<>();

    protected AppUser() {
        // JPA no-arg
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<AppRole> getRoles() {
        return roles;
    }

    // equals/hashCode on surrogate PK only
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppUser other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "AppUser{id=" + id + ", email='" + email + "', active=" + active + "}";
    }
}
