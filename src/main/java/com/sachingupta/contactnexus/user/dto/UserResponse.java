package com.sachingupta.contactnexus.user.dto;

import com.sachingupta.contactnexus.user.User;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String name,
        boolean emailVerified,
        ProfileDto profile,
        Instant createdAt) {

    public static UserResponse from(User u, String avatarUrl) {
        return new UserResponse(
                u.getId(),
                u.getEmail(),
                u.getName(),
                u.isEmailVerified(),
                ProfileDto.from(u.getProfile(), avatarUrl),
                u.getCreatedAt());
    }
}
