package com.vaahansetu.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.vaahansetu.model.User;
import com.vaahansetu.model.UserSession;
import com.vaahansetu.repository.SessionRepository;
import com.vaahansetu.repository.UserRepository;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public record TokenSession(
            Long userId,
            String username,
            String fullName,
            String role,
            Instant issuedAt,
            Instant expiresAt) {

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public record AuthResponse(
            String token,
            Long userId,
            String username,
            String fullName,
            String role,
            String message) {
    }

    public AuthService(UserRepository userRepository) {
        this(userRepository, new SessionRepository(null), new BCryptPasswordEncoder());
    }

    @Autowired
    public AuthService(UserRepository userRepository, SessionRepository sessionRepository) {
        this(userRepository, sessionRepository, new BCryptPasswordEncoder());
    }

    public AuthService(UserRepository userRepository, SessionRepository sessionRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthResponse login(String username, String rawPassword) {
        if (username == null || rawPassword == null || username.isBlank() || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Username and password are required.");
        }

        User user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password."));

        if (Boolean.FALSE.equals(user.getIsActive())) {
            throw new IllegalStateException("Account is inactive. Please contact your system administrator.");
        }

        boolean matched = false;
        try {
            matched = passwordEncoder.matches(rawPassword, user.getPasswordHash());
        } catch (Exception e) {
            matched = false;
        }

        if (!matched) {
            throw new IllegalArgumentException("Invalid username or password.");
        }

        // Generate 32-byte cryptographically secure token
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = "vst_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        // Store SHA-256 hash in persistent session storage, NEVER raw bearer token
        String tokenHash = SessionRepository.hashToken(token);

        Instant now = Instant.now();
        Instant expires = now.plusSeconds(86400 * 7); // 7-day session validity

        sessionRepository.saveSession(
                user.getUserId(),
                tokenHash,
                user.getRole(),
                now,
                expires);

        return new AuthResponse(
                token,
                user.getUserId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                "Authentication successful.");
    }

    public Optional<TokenSession> validateToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }

        String rawToken = authHeader.substring(7).trim();
        if (rawToken.isEmpty()) {
            return Optional.empty();
        }

        String tokenHash = SessionRepository.hashToken(rawToken);
        Optional<UserSession> sessionOpt = sessionRepository.findSessionByTokenHash(tokenHash);

        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }

        UserSession session = sessionOpt.get();

        // Check revocation and expiration
        if (session.getIsRevoked() || session.isExpired()) {
            return Optional.empty();
        }

        // Fresh database validation of user account state and permissions
        Optional<User> userOpt = userRepository.findById(session.getUserId());
        if (userOpt.isEmpty() || Boolean.FALSE.equals(userOpt.get().getIsActive())) {
            return Optional.empty();
        }

        User user = userOpt.get();
        String currentRole = user.getRole() != null ? user.getRole() : session.getRole();

        TokenSession tokenSession = new TokenSession(
                user.getUserId(),
                user.getUsername(),
                user.getFullName(),
                currentRole,
                session.getCreatedAt(),
                session.getExpiresAt());

        return Optional.of(tokenSession);
    }

    public TokenSession requireOfficer(String authHeader) {
        TokenSession session = validateToken(authHeader)
                .orElseThrow(() -> new SecurityException("Unauthorized: Valid officer or admin authentication required."));
        return session;
    }

    public TokenSession requireAdmin(String authHeader) {
        TokenSession session = requireOfficer(authHeader);
        if (!"ADMIN".equalsIgnoreCase(session.role())) {
            throw new SecurityException("Forbidden: Administrator privileges required.");
        }
        return session;
    }

    public void logout(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String rawToken = authHeader.substring(7).trim();
            if (!rawToken.isEmpty()) {
                String tokenHash = SessionRepository.hashToken(rawToken);
                sessionRepository.revokeSession(tokenHash);
            }
        }
    }
}
