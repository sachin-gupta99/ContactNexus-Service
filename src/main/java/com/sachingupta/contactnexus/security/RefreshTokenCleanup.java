package com.sachingupta.contactnexus.security;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nightly sweep for both refresh tokens (expired) and password-reset tokens
 * (expired OR already used). Keeps the two tables from growing without bound
 * over the life of the service.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenRepository passwordResetTokens;
    private final EmailVerificationService emailVerifications;

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();

        int refreshDeleted = refreshTokens.deleteExpiredBefore(now);
        if (refreshDeleted > 0) {
            log.info("Purged {} expired refresh tokens", refreshDeleted);
        }

        int verifyDeleted = emailVerifications.purgeExpiredOrUsed(now);
        if (verifyDeleted > 0) {
            log.info("Purged {} expired or used email-verification tokens", verifyDeleted);
        }

        int resetDeleted = passwordResetTokens.deleteExpiredOrUsed(now);
        if (resetDeleted > 0) {
            log.info("Purged {} expired or used password-reset tokens", resetDeleted);
        }
    }
}
