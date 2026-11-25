package com.sachingupta.contactnexus.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Full replacement of the editable profile. Email, password and avatar are not
 * part of it: they have their own flows. {@code profile.imageUrl} is ignored.
 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 255) String name,
        @Valid ProfileDto profile) {
}
