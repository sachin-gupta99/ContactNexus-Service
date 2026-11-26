package com.sachingupta.contactnexus.contact.dto;

import com.sachingupta.contactnexus.contact.Contact;

import java.time.Instant;
import java.util.List;

/**
 * {@code imageUrl} is either the external URL the user typed or, when
 * {@code hasPhoto} is true, a short-lived presigned URL for the uploaded photo.
 * Clients must not send a presigned URL back as {@code imageUrl}.
 */
public record ContactResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String bio,
        String imageUrl,
        boolean hasPhoto,
        String street,
        String city,
        String state,
        String country,
        String zipCode,
        String githubUrl,
        String linkedinUrl,
        String instagramUrl,
        boolean favorite,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt) {

    public static ContactResponse from(Contact c, String resolvedImageUrl, boolean hasPhoto, List<String> tags) {
        return new ContactResponse(
                c.getId(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getBio(),
                resolvedImageUrl,
                hasPhoto,
                c.getStreet(),
                c.getCity(),
                c.getState(),
                c.getCountry(),
                c.getZipCode(),
                c.getGithubUrl(),
                c.getLinkedinUrl(),
                c.getInstagramUrl(),
                c.isFavorite(),
                tags,
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
