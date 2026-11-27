package com.sachingupta.contactnexus.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    /** False until the emailed link is used. Login is refused until then. */
    @Column(nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** Consecutive wrong passwords since the last successful sign-in or lockout. */
    @Column(nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    /** While in the future, sign-in is refused even with the right password. */
    private Instant lockedUntil;

    /** Email the day before a contact's birthday. */
    @Column(nullable = false)
    @Builder.Default
    private boolean birthdayReminders = true;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Profile profile;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
