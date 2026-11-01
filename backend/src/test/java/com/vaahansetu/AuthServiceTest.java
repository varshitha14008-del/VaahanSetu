package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.vaahansetu.model.User;
import com.vaahansetu.repository.UserRepository;
import com.vaahansetu.service.AuthService;
import com.vaahansetu.service.AuthService.AuthResponse;
import com.vaahansetu.service.AuthService.TokenSession;

public class AuthServiceTest {

    private UserRepository mockUserRepo;
    private AuthService authService;
    private BCryptPasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        mockUserRepo = new UserRepository(null) {
            @Override
            public Optional<User> findByUsername(String username) {
                if ("admin".equals(username)) {
                    return Optional.of(new User(1L, "System Admin", "admin", encoder.encode("secretAdmin"), "ADMIN", true));
                }
                if ("officer".equals(username)) {
                    return Optional.of(new User(2L, "Officer One", "officer", encoder.encode("secretOfficer"), "OFFICER", true));
                }
                if ("inactive".equals(username)) {
                    return Optional.of(new User(3L, "Inactive User", "inactive", encoder.encode("password"), "OFFICER", false));
                }
                return Optional.empty();
            }

            @Override
            public Optional<User> findById(Long userId) {
                if (Long.valueOf(1L).equals(userId)) {
                    return Optional.of(new User(1L, "System Admin", "admin", encoder.encode("secretAdmin"), "ADMIN", true));
                }
                if (Long.valueOf(2L).equals(userId)) {
                    return Optional.of(new User(2L, "Officer One", "officer", encoder.encode("secretOfficer"), "OFFICER", true));
                }
                if (Long.valueOf(3L).equals(userId)) {
                    return Optional.of(new User(3L, "Inactive User", "inactive", encoder.encode("password"), "OFFICER", false));
                }
                return Optional.empty();
            }

            @Override
            public int updatePassword(Long userId, String passwordHash) {
                return 1;
            }
        };
        authService = new AuthService(mockUserRepo);
    }

    @Test
    @DisplayName("Successful login returns secure token and role")
    void testSuccessfulLogin() {
        AuthResponse resp = authService.login("admin", "secretAdmin");
        assertNotNull(resp.token());
        assertTrue(resp.token().startsWith("vst_"));
        assertEquals("ADMIN", resp.role());
        assertEquals("admin", resp.username());
    }

    @Test
    @DisplayName("Invalid password throws IllegalArgumentException")
    void testInvalidPassword() {
        assertThrows(IllegalArgumentException.class, () -> authService.login("admin", "wrongPassword"));
    }

    @Test
    @DisplayName("Inactive user throws IllegalStateException")
    void testInactiveUser() {
        assertThrows(IllegalStateException.class, () -> authService.login("inactive", "password"));
    }

    @Test
    @DisplayName("Role authorization correctly distinguishes ADMIN from OFFICER")
    void testRoleAuthorization() {
        AuthResponse adminResp = authService.login("admin", "secretAdmin");
        AuthResponse officerResp = authService.login("officer", "secretOfficer");

        // Both can access officer-level functions
        TokenSession officerSession = authService.requireOfficer("Bearer " + officerResp.token());
        assertEquals("OFFICER", officerSession.role());

        TokenSession adminSession = authService.requireOfficer("Bearer " + adminResp.token());
        assertEquals("ADMIN", adminSession.role());

        // Only admin can access admin functions
        authService.requireAdmin("Bearer " + adminResp.token());
        assertThrows(SecurityException.class, () -> authService.requireAdmin("Bearer " + officerResp.token()));
    }
}
