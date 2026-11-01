package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.vaahansetu.model.User;
import com.vaahansetu.repository.SessionRepository;
import com.vaahansetu.repository.UserRepository;
import com.vaahansetu.service.AuthService;
import com.vaahansetu.service.AuthService.AuthResponse;

public class AuthSecurityTest {

    private BCryptPasswordEncoder encoder;
    private AtomicBoolean passwordUpdateInvoked;
    private String adminStoredHash;
    private String officerStoredHash;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        passwordUpdateInvoked = new AtomicBoolean(false);

        // Production strong passwords
        adminStoredHash = encoder.encode("ProductionAdmin$StrongSecret2026!");
        officerStoredHash = encoder.encode("ProductionOfficer$StrongSecret2026!");

        UserRepository userRepository = new UserRepository(null) {
            @Override
            public Optional<User> findByUsername(String username) {
                if ("admin".equalsIgnoreCase(username)) {
                    return Optional.of(new User(1L, "System Admin", "admin", adminStoredHash, "ADMIN", true));
                }
                if ("officer1".equalsIgnoreCase(username)) {
                    return Optional.of(new User(2L, "Officer One", "officer1", officerStoredHash, "OFFICER", true));
                }
                return Optional.empty();
            }

            @Override
            public Optional<User> findById(Long userId) {
                if (Long.valueOf(1L).equals(userId)) {
                    return Optional.of(new User(1L, "System Admin", "admin", adminStoredHash, "ADMIN", true));
                }
                if (Long.valueOf(2L).equals(userId)) {
                    return Optional.of(new User(2L, "Officer One", "officer1", officerStoredHash, "OFFICER", true));
                }
                return Optional.empty();
            }

            @Override
            public int updatePassword(Long userId, String passwordHash) {
                passwordUpdateInvoked.set(true);
                return 1;
            }
        };

        SessionRepository sessionRepository = new SessionRepository(null);
        authService = new AuthService(userRepository, sessionRepository, encoder);
    }

    @Test
    @DisplayName("1. admin/admin123 is rejected when stored password hash does not match")
    void testAdminDemoPasswordRejectedWhenHashDiffers() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            authService.login("admin", "admin123");
        });
        assertEquals("Invalid username or password.", ex.getMessage());
        assertFalse(passwordUpdateInvoked.get(), "updatePassword must NOT be invoked on failed login");
    }

    @Test
    @DisplayName("2. officer1/officer123 is rejected when stored password hash does not match")
    void testOfficerDemoPasswordRejectedWhenHashDiffers() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            authService.login("officer1", "officer123");
        });
        assertEquals("Invalid username or password.", ex.getMessage());
        assertFalse(passwordUpdateInvoked.get(), "updatePassword must NOT be invoked on failed login");
    }

    @Test
    @DisplayName("3. A valid BCrypt password succeeds")
    void testValidBCryptPasswordSucceeds() {
        AuthResponse response = authService.login("admin", "ProductionAdmin$StrongSecret2026!");
        assertNotNull(response);
        assertNotNull(response.token());
        assertTrue(response.token().startsWith("vst_"));
        assertEquals("ADMIN", response.role());
        assertFalse(passwordUpdateInvoked.get(), "Successful login must not rewrite the password");
    }

    @Test
    @DisplayName("4. A failed login never modifies the database password hash")
    void testFailedLoginNeverModifiesDatabasePassword() {
        passwordUpdateInvoked.set(false);

        // Attempt with various passwords
        assertThrows(IllegalArgumentException.class, () -> authService.login("admin", "admin123"));
        assertThrows(IllegalArgumentException.class, () -> authService.login("admin", "wrongPass"));
        assertThrows(IllegalArgumentException.class, () -> authService.login("officer1", "officer123"));
        assertThrows(IllegalArgumentException.class, () -> authService.login("officer1", "randomGuess"));

        assertFalse(passwordUpdateInvoked.get(), "Database password hash must never be modified by login attempts");
    }

    @Test
    @DisplayName("5. Existing production password changes remain intact across login cycles")
    void testProductionPasswordRemainsIntact() {
        // Successful login
        AuthResponse okResp = authService.login("officer1", "ProductionOfficer$StrongSecret2026!");
        assertNotNull(okResp.token());
        assertFalse(passwordUpdateInvoked.get());

        // Subsequent unauthorized attempt with old demo credentials
        assertThrows(IllegalArgumentException.class, () -> authService.login("officer1", "officer123"));

        // Password hash is unchanged and original strong password still works
        AuthResponse okResp2 = authService.login("officer1", "ProductionOfficer$StrongSecret2026!");
        assertNotNull(okResp2.token());
        assertFalse(passwordUpdateInvoked.get());
    }
}
