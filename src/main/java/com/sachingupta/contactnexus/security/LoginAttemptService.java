package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.common.AccountLockedException;
import com.sachingupta.contactnexus.email.EmailSender;
import com.sachingupta.contactnexus.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-account lockout: after {@link #MAX_ATTEMPTS} wrong passwords in a row the
 * account refuses sign-in for {@link #LOCK_DURATION}, even with the right
 * password. This complements the per-IP rate limit, which an attacker can dodge
 * by rotating proxies.
 *
 * <p>Emails with no account get the same treatment from an in-memory counter, so
 * the responses can't be used to tell which addresses are registered.
 *
 * <p>Trade-off: anyone who knows an address can lock its owner out for 15
 * minutes. The lock is short, the owner is emailed, and a password reset lifts it.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_UNKNOWN = 10_000;

    private final EmailSender emailSender;

    private final Map<String, UnknownAttempts> unknownEmails = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** @throws AccountLockedException if the account (or unknown email) is currently locked */
    public void assertNotLocked(User user, String email) {
        Instant now = clock.instant();
        Instant lockedUntil = user != null ? user.getLockedUntil() : lockedUntilForUnknown(email);
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw new AccountLockedException(Duration.between(now, lockedUntil).toSeconds());
        }
    }

    /**
     * Counts a wrong password. On the attempt that reaches the limit the account
     * is locked and this throws instead of returning.
     */
    public void recordFailure(User user, String email) {
        Instant now = clock.instant();
        if (user == null) {
            recordUnknownFailure(email, now);
            return;
        }
        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts < MAX_ATTEMPTS) {
            user.setFailedLoginAttempts(attempts);
            return;
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(now.plus(LOCK_DURATION));
        log.warn("Locked user {} for {} after {} failed sign-ins", user.getId(), LOCK_DURATION, MAX_ATTEMPTS);
        AfterCommit.run(() -> sendLockedEmail(user.getEmail(), user.getName()));
        throw new AccountLockedException(LOCK_DURATION.toSeconds());
    }

    /** Called on a successful sign-in, and when a password reset proves inbox ownership. */
    public void clear(User user) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
    }

    private Instant lockedUntilForUnknown(String email) {
        UnknownAttempts a = unknownEmails.get(email);
        return a == null ? null : a.lockedUntil();
    }

    private void recordUnknownFailure(String email, Instant now) {
        if (unknownEmails.size() >= MAX_TRACKED_UNKNOWN) {
            pruneUnknown(now);
        }
        UnknownAttempts next = unknownEmails.compute(email, (k, prev) -> {
            boolean lockOver = prev != null && prev.lockedUntil() != null && !now.isBefore(prev.lockedUntil());
            int count = (prev == null || lockOver) ? 1 : prev.count() + 1;
            return count >= MAX_ATTEMPTS
                    ? new UnknownAttempts(0, now.plus(LOCK_DURATION), now)
                    : new UnknownAttempts(count, null, now);
        });
        if (next.lockedUntil() != null) {
            throw new AccountLockedException(LOCK_DURATION.toSeconds());
        }
    }

    // Bounded so a flood of random addresses can't grow the map without limit.
    private void pruneUnknown(Instant now) {
        Instant staleBefore = now.minus(LOCK_DURATION);
        unknownEmails.entrySet().removeIf(e -> e.getValue().lastFailureAt().isBefore(staleBefore)
                && (e.getValue().lockedUntil() == null || !now.isBefore(e.getValue().lockedUntil())));
        if (unknownEmails.size() >= MAX_TRACKED_UNKNOWN) {
            unknownEmails.clear();
        }
    }

    int trackedUnknownCount() {
        return unknownEmails.size();
    }

    private void sendLockedEmail(String to, String name) {
        emailSender.send(to, "Sign-in to your ContactNexus account was paused", """
                <p>Hi %s,</p>
                <p>There were %d failed attempts to sign in to your ContactNexus account, so sign-in is paused for %d minutes.</p>
                <p>If this was you, wait and try again, or reset your password to sign in straight away.</p>
                <p>If it was not you, the password was not accepted and nothing has changed. Resetting your password is still a good idea.</p>
                <p>- ContactNexus</p>
                """.formatted(Html.escape(name), MAX_ATTEMPTS, LOCK_DURATION.toMinutes()));
    }

    private record UnknownAttempts(int count, Instant lockedUntil, Instant lastFailureAt) {}
}
