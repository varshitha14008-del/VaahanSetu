package com.vaahansetu.model;

import java.time.Instant;

public class UserSession {

    private Long sessionId;
    private Long userId;
    private String tokenHash;
    private String role;
    private Instant createdAt;
    private Instant expiresAt;
    private Boolean isRevoked;
    private Instant revokedAt;

    public UserSession() {
    }

    public UserSession(Long sessionId, Long userId, String tokenHash, String role, Instant createdAt, Instant expiresAt, Boolean isRevoked, Instant revokedAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.role = role;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.isRevoked = isRevoked;
        this.revokedAt = revokedAt;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Boolean getIsRevoked() {
        return isRevoked != null && isRevoked;
    }

    public void setIsRevoked(Boolean isRevoked) {
        this.isRevoked = isRevoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public boolean isValid() {
        return !getIsRevoked() && !isExpired();
    }
}
