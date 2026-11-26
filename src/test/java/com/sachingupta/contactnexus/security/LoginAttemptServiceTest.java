package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.common.AccountLockedException;
import com.sachingupta.contactnexus.email.EmailSender;
import com.sachingupta.contactnexus.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LoginAttemptServiceTest {

    private final EmailSender emailSender = mock(EmailSender.class);
    private final LoginAttemptService service = new LoginAttemptService(emailSender);
    private Instant now = Instant.parse("2026-01-01T10:00:00Z");

    @BeforeEach
    void fixedClock() {
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    private void advance(Duration d) {
        now = now.plus(d);
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    private static User user() {
        return User.builder().id(1L).email("ann@x.com").name("Ann").passwordHash("x").build();
    }

    private void failTimes(User user, String email, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(user, email);
        }
    }

    @Test
    void locksTheAccountOnTheFifthFailureAndEmailsTheOwner() {
        User u = user();
        failTimes(u, u.getEmail(), LoginAttemptService.MAX_ATTEMPTS - 1);
        assertThat(u.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(u.getLockedUntil()).isNull();

        assertThatThrownBy(() -> service.recordFailure(u, u.getEmail()))
                .isInstanceOf(AccountLockedException.class)
                .hasMessageContaining("15 minutes");
        assertThat(u.getLockedUntil()).isEqualTo(now.plus(LoginAttemptService.LOCK_DURATION));
        assertThat(u.getFailedLoginAttempts()).isZero();
        verify(emailSender).send(eq("ann@x.com"), anyString(), anyString());
    }

    @Test
    void lockedAccountRefusesUntilTheLockExpires() {
        User u = user();
        assertThatThrownBy(() -> failTimes(u, u.getEmail(), 5)).isInstanceOf(AccountLockedException.class);

        advance(Duration.ofMinutes(14));
        assertThatThrownBy(() -> service.assertNotLocked(u, u.getEmail()))
                .isInstanceOf(AccountLockedException.class)
                .hasMessageContaining("1 minute,");

        advance(Duration.ofMinutes(1));
        assertThatCode(() -> service.assertNotLocked(u, u.getEmail())).doesNotThrowAnyException();
    }

    @Test
    void successResetsTheCounter() {
        User u = user();
        failTimes(u, u.getEmail(), 4);
        service.clear(u);
        failTimes(u, u.getEmail(), 4);
        assertThat(u.getLockedUntil()).isNull();
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void unknownEmailsLockTheSameWaySoResponsesMatch() {
        String ghost = "nobody@x.com";
        failTimes(null, ghost, 4);
        assertThatThrownBy(() -> service.recordFailure(null, ghost))
                .isInstanceOf(AccountLockedException.class)
                .hasMessageContaining("15 minutes");
        assertThatThrownBy(() -> service.assertNotLocked(null, ghost)).isInstanceOf(AccountLockedException.class);

        advance(LoginAttemptService.LOCK_DURATION);
        assertThatCode(() -> service.assertNotLocked(null, ghost)).doesNotThrowAnyException();
        // A fresh window starts after the lock: four more failures don't lock again.
        assertThatCode(() -> failTimes(null, ghost, 4)).doesNotThrowAnyException();
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void unknownEmailTrackingIsBounded() {
        for (int i = 0; i < 10_500; i++) {
            service.recordFailure(null, "random" + i + "@x.com");
        }
        assertThat(service.trackedUnknownCount()).isLessThanOrEqualTo(10_000);
    }
}
