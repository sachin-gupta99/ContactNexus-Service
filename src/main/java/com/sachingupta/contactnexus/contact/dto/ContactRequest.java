package com.sachingupta.contactnexus.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactRequest(
        @NotBlank String firstName,
        String lastName,
        @NotBlank @Email String email,
        String phone,
        @Size(max = 1000) String bio,
        String imageUrl,
        String street,
        String city,
        String state,
        String country,
        String zipCode,
        String githubUrl,
        String linkedinUrl,
        String instagramUrl) {
}
