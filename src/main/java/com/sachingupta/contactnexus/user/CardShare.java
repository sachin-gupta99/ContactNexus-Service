package com.sachingupta.contactnexus.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** The public link to a user's own contact card and which fields it shows. */
@Entity
@Table(name = "card_shares")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardShare {

    @Id
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** Comma-separated {@link CardShareService.Field} names. */
    @Column(nullable = false, length = 500)
    private String fields;

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
