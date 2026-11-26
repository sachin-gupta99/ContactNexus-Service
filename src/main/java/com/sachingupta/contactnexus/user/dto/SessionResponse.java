package com.sachingupta.contactnexus.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One signed-in device: a refresh-token family. {@code lastActiveAt} is when its
 * newest token was issued, i.e. the last time that device refreshed (at least
 * every 15 minutes while the app is open).
 */
public record SessionResponse(
        UUID id,
        String userAgent,
        String ipAddress,
        Instant signedInAt,
        Instant lastActiveAt,
        Instant expiresAt,
        boolean current) {
}
