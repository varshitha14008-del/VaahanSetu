package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.vaahansetu.model.User;
import com.vaahansetu.model.UserSession;
import com.vaahansetu.repository.SessionRepository;
import com.vaahansetu.repository.UserRepository;
import com.vaahansetu.service.AuthService;
import com.vaahansetu.service.AuthService.AuthResponse;
import com.vaahansetu.service.AuthService.TokenSession;

public class SessionPersistenceTest {

    private BCryptPasswordEncoder encoder;
    private SessionRepository sessionRepository;
    private UserRepository userRepository;
    private AuthService authService;
    private AtomicReference<Boolean> userActive;
    private AtomicReference<String> userRole;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        sessionRepository = new SessionRepository(null);
        userActive = new AtomicReference<>(true);
        userRole = new AtomicReference<>("OFFICER");

        userRepository = new UserRepository(null) {
            @Override
            public Optional<User> findByUsername(String username) {
                if ("testuser".equals(username)) {
                    return Optional.of(new User(10L, "Test User", "testuser", encoder.encode("password123"), userRole.get(), userActive.get()));
                }
                return Optional.empty();
            }

            @Override
            public Optional<User> findById(Long userId) {
                if (Long.valueOf(10L).equals(userId)) {
                    return Optional.of(new User(10L, "Test User", "testuser", encoder.encode("password123"), userRole.get(), userActive.get()));
                }
                return Optional.empty();
            }
        };

        authService = new AuthService(userRepository, sessionRepository, encoder);
    }

    @Test
    @DisplayName("Token is cryptographically generated and stored as SHA-256 hash")
    void testTokenHashingAndFormat() {
        AuthResponse response = authService.login("testuser", "password123");
        String rawToken = response.token();
        assertTrue(rawToken.startsWith("vst_"));
        assertTrue(rawToken.length() >= 40);

        String hash = SessionRepository.hashToken(rawToken);
        assertEquals(64, hash.length(), "SHA-256 hash in hex must be 64 characters");

        // Stored session has the hash, NOT the raw token
        Optional<UserSession> sessionOpt = sessionRepository.findSessionByTokenHash(hash);
        assertTrue(sessionOpt.isPresent());
        assertEquals(hash, sessionOpt.get().getTokenHash());
        assertNotEquals(rawToken, sessionOpt.get().getTokenHash());
    }

    @Test
    @DisplayName("Logout genuinely revokes the token preventing reuse")
    void testLogoutRevokesToken() {
        AuthResponse response = authService.login("testuser", "password123");
        String authHeader = "Bearer " + response.token();

        // Valid initially
        assertTrue(authService.validateToken(authHeader).isPresent());

        // Logout
        authService.logout(authHeader);

        // Invalid after logout
        assertTrue(authService.validateToken(authHeader).isEmpty());
        assertThrows(SecurityException.class, () -> authService.requireOfficer(authHeader));
    }

    @Test
    @DisplayName("Expired token is rejected")
    void testExpiredTokenRejected() {
        AuthResponse response = authService.login("testuser", "password123");
        String rawToken = response.token();
        String hash = SessionRepository.hashToken(rawToken);

        // Manually set expiration to past
        UserSession session = sessionRepository.findSessionByTokenHash(hash).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(3600));

        assertTrue(authService.validateToken("Bearer " + rawToken).isEmpty());
    }

    @Test
    @DisplayName("Deactivating a user account immediately invalidates existing active tokens")
    void testDeactivatedUserInvalidatesSession() {
        AuthResponse response = authService.login("testuser", "password123");
        String authHeader = "Bearer " + response.token();

        assertTrue(authService.validateToken(authHeader).isPresent());

        // Deactivate user in database
        userActive.set(false);

        // Token must now be rejected
        assertTrue(authService.validateToken(authHeader).isEmpty());
        assertThrows(SecurityException.class, () -> authService.requireOfficer(authHeader));
    }

    @Test
    @DisplayName("Role change in database dynamically updates session authorization")
    void testDynamicRoleChange() {
        AuthResponse response = authService.login("testuser", "password123");
        String authHeader = "Bearer " + response.token();

        TokenSession s1 = authService.requireOfficer(authHeader);
        assertEquals("OFFICER", s1.role());
        assertThrows(SecurityException.class, () -> authService.requireAdmin(authHeader));

        // Promote user in database to ADMIN
        userRole.set("ADMIN");

        TokenSession s2 = authService.requireAdmin(authHeader);
        assertEquals("ADMIN", s2.role());
    }
}
