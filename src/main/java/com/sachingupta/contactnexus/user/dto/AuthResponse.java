package com.sachingupta.contactnexus.user.dto;

public record AuthResponse(
        String accessToken,
        long expiresInSeconds,
        UserResponse user) {
}
