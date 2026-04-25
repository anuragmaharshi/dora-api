package com.dora.security;

import com.dora.entities.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Wraps {@link AppUser} as a Spring Security {@link UserDetails}.
 *
 * Spring Security's {@code hasRole('X')} expression checks for authority "ROLE_X",
 * so we prefix each role code with "ROLE_" when constructing authorities.
 * The JWT filter builds this directly from token claims — no DB round-trip on
 * every request.
 */
public final class CustomUserDetails implements UserDetails {

    private final AppUser user;
    private final List<GrantedAuthority> authorities;

    public CustomUserDetails(AppUser user) {
        this.user = user;
        // Map each AppRole code to "ROLE_<code>" so Spring's hasRole() expressions work.
        this.authorities = user.getRoles().stream()
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.getCode()))
            .toList();
    }

    /** Convenience constructor used by JwtAuthFilter when building from JWT claims. */
    public CustomUserDetails(AppUser user, List<String> roleCodes) {
        this.user = user;
        this.authorities = roleCodes.stream()
            .map(code -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + code))
            .toList();
    }

    public AppUser getAppUser() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /** Returns the BCrypt hash — Spring Security uses this in {@code DaoAuthenticationProvider}. */
    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    /** Login identifier is email for this application. */
    @Override
    public String getUsername() {
        return user.getEmail();
    }

    /**
     * {@code active=false} deactivates the account without hard-deleting it
     * (LLD-02 §5 retention rules).
     */
    @Override
    public boolean isEnabled() {
        return user.isActive();
    }
}
