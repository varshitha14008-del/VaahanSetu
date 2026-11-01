package com.vaahansetu.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.vaahansetu.model.UserSession;

@Repository
public class SessionRepository {

    private static final Logger logger = LoggerFactory.getLogger(SessionRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, UserSession> memoryFallbackSessions = new ConcurrentHashMap<>();
    private Boolean tableExists = null;

    private final RowMapper<UserSession> sessionRowMapper = (rs, rowNum) -> {
        UserSession s = new UserSession();
        s.setSessionId(rs.getLong("session_id"));
        s.setUserId(rs.getLong("user_id"));
        s.setTokenHash(rs.getString("token_hash"));
        s.setRole(rs.getString("role"));
        Timestamp createdTs = rs.getTimestamp("created_at");
        s.setCreatedAt(createdTs != null ? createdTs.toInstant() : null);
        Timestamp expiresTs = rs.getTimestamp("expires_at");
        s.setExpiresAt(expiresTs != null ? expiresTs.toInstant() : null);
        s.setIsRevoked(rs.getBoolean("is_revoked"));
        Timestamp revokedTs = rs.getTimestamp("revoked_at");
        s.setRevokedAt(revokedTs != null ? revokedTs.toInstant() : null);
        return s;
    };

    public SessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static String hashToken(String rawToken) {
        if (rawToken == null) {
            throw new IllegalArgumentException("Token cannot be null.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available in JVM.", e);
        }
    }

    public synchronized boolean isPersistentStorageAvailable() {
        if (tableExists != null) {
            return tableExists;
        }
        try {
            String checkSql = "SELECT 1 FROM information_schema.tables WHERE table_name = 'user_sessions' LIMIT 1";
            List<Integer> list = jdbcTemplate.query(checkSql, (rs, rowNum) -> rs.getInt(1));
            tableExists = !list.isEmpty();
            if (Boolean.TRUE.equals(tableExists)) {
                logger.info("Persistent session storage initialized successfully with table 'user_sessions'.");
            } else {
                logger.warn("Table 'user_sessions' was not found in the database. Operating with secure hashed in-memory session cache. Please apply migration 002_user_sessions.sql.");
            }
        } catch (Exception e) {
            logger.warn("Could not query database metadata for 'user_sessions': {}. Operating with in-memory session cache.", e.getMessage());
            tableExists = false;
        }
        return tableExists;
    }

    public void saveSession(Long userId, String tokenHash, String role, Instant createdAt, Instant expiresAt) {
        if (isPersistentStorageAvailable()) {
            try {
                String sql = """
                        INSERT INTO user_sessions (user_id, token_hash, role, created_at, expires_at, is_revoked)
                        VALUES (?, ?, ?::user_role, ?, ?, FALSE)
                        """;
                jdbcTemplate.update(sql, userId, tokenHash, role, Timestamp.from(createdAt), Timestamp.from(expiresAt));
                return;
            } catch (DataAccessException ex) {
                logger.error("Failed to persist session to database, falling back to secure in-memory cache: {}", ex.getMessage());
            }
        }

        UserSession memorySession = new UserSession(null, userId, tokenHash, role, createdAt, expiresAt, false, null);
        memoryFallbackSessions.put(tokenHash, memorySession);
    }

    public Optional<UserSession> findSessionByTokenHash(String tokenHash) {
        if (isPersistentStorageAvailable()) {
            try {
                String sql = """
                        SELECT session_id, user_id, token_hash, role::text, created_at, expires_at, is_revoked, revoked_at
                        FROM user_sessions
                        WHERE token_hash = ?
                        """;
                List<UserSession> list = jdbcTemplate.query(sql, sessionRowMapper, tokenHash);
                if (!list.isEmpty()) {
                    return Optional.of(list.get(0));
                }
            } catch (DataAccessException ex) {
                logger.error("Database error looking up session: {}", ex.getMessage());
            }
        }

        UserSession memorySession = memoryFallbackSessions.get(tokenHash);
        return Optional.ofNullable(memorySession);
    }

    public boolean revokeSession(String tokenHash) {
        boolean revoked = false;
        if (isPersistentStorageAvailable()) {
            try {
                String sql = "UPDATE user_sessions SET is_revoked = TRUE, revoked_at = NOW() WHERE token_hash = ?";
                int count = jdbcTemplate.update(sql, tokenHash);
                revoked = count > 0;
            } catch (DataAccessException ex) {
                logger.error("Database error revoking session: {}", ex.getMessage());
            }
        }

        UserSession memorySession = memoryFallbackSessions.get(tokenHash);
        if (memorySession != null) {
            memorySession.setIsRevoked(true);
            memorySession.setRevokedAt(Instant.now());
            revoked = true;
        }

        return revoked;
    }

    public void revokeAllUserSessions(Long userId) {
        if (isPersistentStorageAvailable()) {
            try {
                String sql = "UPDATE user_sessions SET is_revoked = TRUE, revoked_at = NOW() WHERE user_id = ? AND is_revoked = FALSE";
                jdbcTemplate.update(sql, userId);
            } catch (DataAccessException ex) {
                logger.error("Database error revoking all sessions for user {}: {}", userId, ex.getMessage());
            }
        }

        memoryFallbackSessions.values().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .forEach(s -> {
                    s.setIsRevoked(true);
                    s.setRevokedAt(Instant.now());
                });
    }

    public void clearMemoryCache() {
        memoryFallbackSessions.clear();
    }
}
