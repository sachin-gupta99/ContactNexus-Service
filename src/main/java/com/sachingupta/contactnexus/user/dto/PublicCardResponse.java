package com.sachingupta.contactnexus.user.dto;

/** Only the fields the owner chose; everything else is null. */
public record PublicCardResponse(
        String name,
        String email,
        String phone,
        String work,
        String street,
        String area,
        String city,
        String state,
        String pincode,
        String github,
        String linkedin,
        String instagram,
        String bioHeading,
        String bioDescription,
        String photoUrl) {
}
