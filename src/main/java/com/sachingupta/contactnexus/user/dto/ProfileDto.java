package com.sachingupta.contactnexus.user.dto;

import com.sachingupta.contactnexus.user.Profile;
import jakarta.validation.constraints.Size;

/**
 * Used for both the profile block on signup and the profile block on responses.
 * Limits match the column sizes, so oversized input is a 400 rather than a
 * database error.
 */
public record ProfileDto(
        @Size(max = 255) String work,
        @Size(max = 255) String phone,
        @Size(max = 255) String street,
        @Size(max = 255) String area,
        @Size(max = 255) String city,
        @Size(max = 255) String state,
        @Size(max = 255) String pincode,
        @Size(max = 255) String github,
        @Size(max = 255) String linkedin,
        @Size(max = 255) String instagram,
        @Size(max = 255) String likes,
        @Size(max = 255) String movie,
        @Size(max = 255) String interests,
        @Size(max = 255) String bioHeading,
        @Size(max = 1000) String bioDescription,
        @Size(max = 255) String imageUrl) {

    /**
     * @param imageUrl the URL clients should render, already resolved (presigned for
     *                 S3 avatars) because the entity only holds the object key
     */
    public static ProfileDto from(Profile p, String imageUrl) {
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
                imageUrl);
    }
}
