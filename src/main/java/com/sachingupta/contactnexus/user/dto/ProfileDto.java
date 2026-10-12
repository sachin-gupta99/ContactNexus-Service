package com.sachingupta.contactnexus.user.dto;

import com.sachingupta.contactnexus.user.Profile;
import jakarta.validation.constraints.Size;

/**
 * Used for both the profile block on signup and the profile block on responses.
 */
public record ProfileDto(
        String work,
        String phone,
        String street,
        String area,
        String city,
        String state,
        String pincode,
        String github,
        String linkedin,
        String instagram,
        String likes,
        String movie,
        String interests,
        String bioHeading,
        @Size(max = 1000) String bioDescription,
        String imageUrl) {

    public static ProfileDto from(Profile p) {
        if (p == null) {
            return null;
        }
        return new ProfileDto(
                p.getWork(),
                p.getPhone(),
                p.getStreet(),
                p.getArea(),
                p.getCity(),
                p.getState(),
                p.getPincode(),
                p.getGithub(),
                p.getLinkedin(),
                p.getInstagram(),
                p.getLikes(),
                p.getMovie(),
                p.getInterests(),
                p.getBioHeading(),
                p.getBioDescription(),
                p.getImageUrl());
    }
}
