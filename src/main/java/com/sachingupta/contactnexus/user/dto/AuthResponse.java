package com.sachingupta.contactnexus.user.dto;

public record AuthResponse(
        String token,
        UserResponse user) {
}
