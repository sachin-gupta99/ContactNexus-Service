package com.sachingupta.contactnexus.contact;

import jakarta.persistence.*;
import lombok.*;

import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "contacts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String firstName;

    private String lastName;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Column(length = 1000)
    private String bio;

    private String imageUrl;

    /** S3 key of an uploaded photo; wins over {@link #imageUrl} when set. */
    private String imageKey;

    private String street;
    private String city;
    private String state;
    private String country;
    private String zipCode;

    private String githubUrl;
    private String linkedinUrl;
    private String instagramUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean favorite = false;

    // Batch-fetched so a page of 12 contacts loads its tags in one extra query, not 12.
    @ManyToMany
    @JoinTable(name = "contact_tags",
            joinColumns = @JoinColumn(name = "contact_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @BatchSize(size = 50)
    @Builder.Default
    private Set<Tag> tags = new HashSet<>();

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
