package com.dora.thorough.boundaries;

import com.dora.entities.AppRole;
import com.dora.entities.AppUser;
import com.dora.entities.Tenant;
import com.dora.security.DevJwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dora.security.RoleNames.BOARD_VIEWER;
import static com.dora.security.RoleNames.CISO;
import static com.dora.security.RoleNames.COMPLIANCE_OFFICER;
import static com.dora.security.RoleNames.INCIDENT_MANAGER;
import static com.dora.security.RoleNames.OPS_ANALYST;
import static com.dora.security.RoleNames.PLATFORM_ADMIN;
import static com.dora.security.RoleNames.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Thorough unit tests for DevJwtService.
 *
 * Covers: issue/verify round-trip, tamper detection, expiration boundaries,
 * clock-skew edge cases, role determinism, weak key rejection, and null/empty input.
 */
class DevJwtServiceTest {

    // Valid 32-byte secret (256 bits — minimum for HS256).
    private static final String VALID_SECRET = "test-secret-change-me-min-32-char";

    private DevJwtService jwtService;
    private AppUser testUser;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new DevJwtService(VALID_SECRET, 60L, 60L);
        testUser = buildUser("ops@dora.local", OPS_ANALYST);
    }

    // ── AC-1: issue/verify round-trip ─────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("Issue then verify: sub=email, roles and tenant_id claims match user")
    void issueAndVerify_claimsMatchUser() {
        String token = jwtService.issue(testUser);
        Claims claims = jwtService.verify(token);

        assertThat(claims.getSubject()).isEqualTo("ops@dora.local");
        assertThat(claims.get("tenant_id", String.class))
                .isEqualTo("00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).containsExactly(OPS_ANALYST);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("Issued token is a compact 3-part base64url structure")
    void issuedToken_hasThreeParts() {
        String token = jwtService.issue(testUser);
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("Issued token iat is not in the future and exp is ~60 minutes ahead")
    void issuedToken_iatAndExpTimestampsAreCorrect() {
        Instant before = Instant.now().minusSeconds(2);
        String token = jwtService.issue(testUser);
        Instant after = Instant.now().plusSeconds(2);

        Claims claims = jwtService.verify(token);
        Instant iat = claims.getIssuedAt().toInstant();
        Instant exp = claims.getExpiration().toInstant();

        assertThat(iat).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
        // TTL is 60 minutes — exp must be ~3600s after iat
        long delta = exp.getEpochSecond() - iat.getEpochSecond();
        assertThat(delta).isBetween(3598L, 3602L);
    }

    // ── AC-5: tamper detection ────────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Tampered payload segment (base64 flip) causes JwtException on verify")
    void verify_tamperedPayload_throwsJwtException() {
        String token = jwtService.issue(testUser);
        String[] parts = token.split("\\.");
        // Corrupt the payload segment by appending an extra character
        String tampered = parts[0] + "." + parts[1] + "X" + "." + parts[2];

        assertThatThrownBy(() -> jwtService.verify(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Token signed with a different secret causes JwtException on verify")
    void verify_wrongSecret_throwsJwtException() throws Exception {
        DevJwtService otherService = new DevJwtService(
                "other-secret-change-me-min-32-char", 60L, 60L);
        String foreignToken = otherService.issue(testUser);

        assertThatThrownBy(() -> jwtService.verify(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Token with replaced signature segment causes JwtException on verify")
    void verify_replacedSignature_throwsJwtException() {
        String token = jwtService.issue(testUser);
        String[] parts = token.split("\\.");
        // Swap in a plausible-looking but incorrect signature
        String badSig = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String tampered = parts[0] + "." + parts[1] + "." + badSig;

        assertThatThrownBy(() -> jwtService.verify(tampered))
                .isInstanceOf(JwtException.class);
    }

    // ── AC-5: expiration ──────────────────────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Token with TTL=0 (zero minutes) is immediately expired (no skew) → JwtException")
    void verify_zeroTtlNoSkew_throwsJwtExceptionImmediately() throws Exception {
        DevJwtService zeroTtlService = new DevJwtService(VALID_SECRET, 0L, 0L);
        String token = zeroTtlService.issue(testUser);

        assertThatThrownBy(() -> zeroTtlService.verify(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Token expired 30s ago with 60s clock-skew tolerance is still valid")
    void verify_expired30sAgoWith60sSkew_isAccepted() throws Exception {
        // Issue with TTL=-30s: expired 30 seconds in the past.
        // Skew = 60s, so it should still pass.
        DevJwtService pastService = new DevJwtService(VALID_SECRET, 0L, 60L);
        // Build token manually using the same key with exp = now - 30s
        String token = buildTokenWithCustomExpiry(VALID_SECRET, Instant.now().minusSeconds(30));

        // Verify using a service that has 60s skew
        Claims claims = pastService.verify(token);
        assertThat(claims.getSubject()).isEqualTo("ops@dora.local");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Token expired 61s ago with 60s clock-skew tolerance is rejected → JwtException")
    void verify_expired61sAgoWith60sSkew_throwsJwtException() throws Exception {
        DevJwtService skewService = new DevJwtService(VALID_SECRET, 60L, 60L);
        String token = buildTokenWithCustomExpiry(VALID_SECRET, Instant.now().minusSeconds(61));

        assertThatThrownBy(() -> skewService.verify(token))
                .isInstanceOf(JwtException.class);
    }

    // ── AC-1: roles determinism ────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("Multiple roles in the JWT are sorted alphabetically (deterministic order)")
    void issue_multipleRoles_returnedSorted() {
        // Add roles in reverse-alphabetical order to the user to verify sorting
        AppUser multiRoleUser = buildUserWithRoles(
                "multi@dora.local",
                OPS_ANALYST, CISO, BOARD_VIEWER, INCIDENT_MANAGER, COMPLIANCE_OFFICER);
        String token = jwtService.issue(multiRoleUser);
        Claims claims = jwtService.verify(token);

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).isSorted();
        assertThat(roles).containsExactly(
                BOARD_VIEWER, CISO, COMPLIANCE_OFFICER, INCIDENT_MANAGER, OPS_ANALYST);
    }

    @Test
    @Tag("AC-1")
    @DisplayName("Issuing the same user twice produces identically ordered roles both times")
    void issue_sameUserTwice_roleOrderIsStable() {
        AppUser user = buildUserWithRoles(
                "ops2@dora.local", PLATFORM_ADMIN, OPS_ANALYST, SYSTEM);
        String token1 = jwtService.issue(user);
        String token2 = jwtService.issue(user);

        @SuppressWarnings("unchecked")
        List<String> roles1 = jwtService.verify(token1).get("roles", List.class);
        @SuppressWarnings("unchecked")
        List<String> roles2 = jwtService.verify(token2).get("roles", List.class);

        assertThat(roles1).isEqualTo(roles2);
    }

    // ── WeakKeyException on short secret ──────────────────────────────────────

    @Test
    @Tag("AC-5")
    @DisplayName("Constructor with secret shorter than 32 bytes throws WeakKeyException at startup")
    void constructor_shortSecret_throwsWeakKeyException() {
        // "short" is only 5 bytes — well below the 256-bit HS256 minimum
        assertThatThrownBy(() -> new DevJwtService("short", 60L, 60L))
                .isInstanceOf(WeakKeyException.class);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Constructor with exactly 31-byte secret (255 bits) throws WeakKeyException")
    void constructor_31ByteSecret_throwsWeakKeyException() {
        String exactly31 = "a".repeat(31); // 31 ASCII bytes = 248 bits
        assertThatThrownBy(() -> new DevJwtService(exactly31, 60L, 60L))
                .isInstanceOf(WeakKeyException.class);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("Constructor with exactly 32-byte secret (256 bits) succeeds")
    void constructor_32ByteSecret_succeeds() throws Exception {
        String exactly32 = "a".repeat(32); // 32 ASCII bytes = 256 bits — minimum
        DevJwtService service = new DevJwtService(exactly32, 60L, 60L);
        assertThat(service).isNotNull();
    }

    // ── Null / empty token ────────────────────────────────────────────────────

    @ParameterizedTest(name = "verify(\"{0}\") → JwtException")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not.a.jwt", "Bearer token.here.x"})
    @Tag("AC-5")
    @DisplayName("verify with null, empty, blank, or non-JWT string throws JwtException")
    void verify_nullEmptyOrMalformed_throwsJwtException(String badToken) {
        assertThatThrownBy(() -> jwtService.verify(badToken))
                .isInstanceOf(Exception.class); // JwtException or IllegalArgumentException
    }

    // ── expiresAt helper ──────────────────────────────────────────────────────

    @Test
    @Tag("AC-1")
    @DisplayName("expiresAt() returns an Instant approximately TTL minutes in the future")
    void expiresAt_returnsFutureInstant() throws Exception {
        DevJwtService service = new DevJwtService(VALID_SECRET, 30L, 60L);
        Instant before = Instant.now();
        Instant exp = service.expiresAt();
        Instant after = Instant.now();

        long minExpected = before.plusSeconds(30 * 60 - 2).getEpochSecond();
        long maxExpected = after.plusSeconds(30 * 60 + 2).getEpochSecond();
        assertThat(exp.getEpochSecond()).isBetween(minExpected, maxExpected);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Builds a token signed with the given secret and a custom expiry. */
    private String buildTokenWithCustomExpiry(String secret, Instant expiry) throws Exception {
        // Use JJWT directly to build a token with arbitrary expiry
        javax.crypto.SecretKey key =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return io.jsonwebtoken.Jwts.builder()
                .subject("ops@dora.local")
                .issuedAt(Date.from(expiry.minusSeconds(60)))
                .expiration(Date.from(expiry))
                .claim("roles", List.of(OPS_ANALYST))
                .claim("tenant_id", "00000000-0000-0000-0000-000000000001")
                .signWith(key)
                .compact();
    }

    private AppUser buildUser(String email, String roleCode) throws Exception {
        return buildUserWithRoles(email, roleCode);
    }

    private AppUser buildUserWithRoles(String email, String... roleCodes) {
        Tenant tenant = buildTenant();
        AppUser user = newInstance(AppUser.class);
        setField(user, "id", UUID.fromString("00000000-0000-0000-0001-000000000001"));
        setField(user, "email", email);
        setField(user, "username", email.split("@")[0]);
        setField(user, "passwordHash", "$2a$10$hash");
        setField(user, "active", true);
        setField(user, "mfaEnabled", false);
        setField(user, "tenant", tenant);
        Set<AppRole> roles = new java.util.HashSet<>();
        for (String code : roleCodes) {
            roles.add(new AppRole(code, code + " description"));
        }
        setField(user, "roles", roles);
        return user;
    }

    private Tenant buildTenant() {
        Tenant tenant = new Tenant("Nexus Bank", "NEXUSBANK0000000001");
        setField(tenant, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return tenant;
    }

    private static <T> T newInstance(Class<T> cls) {
        try {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Could not instantiate " + cls, e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not set field '" + fieldName + "' on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> search = clazz;
        while (search != null) {
            for (Field f : search.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
            search = search.getSuperclass();
        }
        throw new RuntimeException("Field not found: " + name);
    }
}
