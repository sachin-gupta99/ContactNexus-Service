package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "password_reset_tokens", indexes = {
        @Index(name = "idx_pwd_reset_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_pwd_reset_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Set exactly once, when the token is spent. {@code null} = unused. */
    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(length = 64)
    private String requestedIp;

    @Column(length = 512)
    private String userAgent;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired() {
        return !Instant.now().isBefore(expiresAt);
    }
}
