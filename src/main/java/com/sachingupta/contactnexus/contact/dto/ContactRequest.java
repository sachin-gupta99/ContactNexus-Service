package com.sachingupta.contactnexus.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Limits mirror the column sizes so an over-long value is a 400, not a database error.
 *
 * <p>{@code tags}: {@code null} leaves an existing contact's tags unchanged (so older
 * clients that don't send the field can't wipe them); an empty list clears them.
 */
public record ContactRequest(
        @NotBlank @Size(max = 255) String firstName,
        @Size(max = 255) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @Size(max = 255) String phone,
        @Size(max = 1000) String bio,
        @Size(max = 255) String imageUrl,
        @Size(max = 255) String street,
        @Size(max = 255) String city,
        @Size(max = 255) String state,
        @Size(max = 255) String country,
        @Size(max = 255) String zipCode,
        @Size(max = 255) String githubUrl,
        @Size(max = 255) String linkedinUrl,
        @Size(max = 255) String instagramUrl,
        @Size(max = 10) List<@Size(max = 30) String> tags) {
}
