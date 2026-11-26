package com.sachingupta.contactnexus.common;

import lombok.Getter;

/** Too many failed sign-ins for one account. Mapped to 429 with Retry-After. */
@Getter
public class AccountLockedException extends RuntimeException {
    private final long retryAfterSeconds;

    public AccountLockedException(long retryAfterSeconds) {
        super(message(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static String message(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return "Too many failed sign-in attempts. Try again in %d minute%s, or reset your password."
                .formatted(minutes, minutes == 1 ? "" : "s");
    }
}
