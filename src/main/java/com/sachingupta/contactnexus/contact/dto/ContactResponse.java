package com.sachingupta.contactnexus.contact.dto;

import com.sachingupta.contactnexus.contact.Contact;

import java.time.Instant;

public record ContactResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String bio,
        String imageUrl,
        String street,
        String city,
        String state,
        String country,
        String zipCode,
        String githubUrl,
        String linkedinUrl,
        String instagramUrl,
        Instant createdAt,
        Instant updatedAt) {

    public static ContactResponse from(Contact c) {
        return new ContactResponse(
                c.getId(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getBio(),
                c.getImageUrl(),
                c.getStreet(),
                c.getCity(),
                c.getState(),
                c.getCountry(),
                c.getZipCode(),
                c.getGithubUrl(),
                c.getLinkedinUrl(),
                c.getInstagramUrl(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
