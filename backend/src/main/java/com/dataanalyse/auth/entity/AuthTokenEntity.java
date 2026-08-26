package com.dataanalyse.auth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "auth_tokens")
public class AuthTokenEntity {
    @Id @Column(length = 64) private String token;
    @Column(name = "username", nullable = false, length = 64) private String username;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
