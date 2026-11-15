package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_refresh_token_user_id", columnList = "user_id"),
        @Index(name = "idx_refresh_token_family_id", columnList = "familyId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Groups a chain of rotated tokens so reuse detection can revoke all of them at once. */
    @Column(nullable = false)
    private UUID familyId;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    /** When this token was rotated, the hash of the token that replaced it. */
    @Column(length = 64)
    private String replacedByHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 64)
    private String ipAddress;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null && Instant.now().isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
