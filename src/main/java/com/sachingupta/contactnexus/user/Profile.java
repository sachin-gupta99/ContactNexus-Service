package com.sachingupta.contactnexus.user;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    private String work;
    private String phone;

    private String street;
    private String area;
    private String city;
    private String state;
    private String pincode;

    private String github;
    private String linkedin;
    private String instagram;

    private String likes;
    private String movie;
    private String interests;

    private String bioHeading;

    @Column(length = 1000)
    private String bioDescription;

    /** Externally hosted image supplied as a plain URL. */
    private String imageUrl;

    /** S3 object key of an uploaded avatar; takes precedence over {@link #imageUrl}. */
    @Column(length = 512)
    private String imageKey;
}
