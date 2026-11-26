package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.email.EmailSender;
import com.sachingupta.contactnexus.user.User;
import com.sachingupta.contactnexus.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final EmailSender emailSender;
    private final LoginAttemptService loginAttempts;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.password-reset.ttl:PT30M}")
    private Duration ttl;

    /**
     * Enumeration-safe: silently no-ops if the email isn't registered. The caller
     * returns 200 either way so an attacker can't probe which emails are valid.
     */
    public void requestReset(String rawEmail, HttpServletRequest request) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        users.findByEmailIgnoreCase(email).ifPresent(user -> issueAndSend(user, request));
    }

    private void issueAndSend(User user, HttpServletRequest request) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(ttl);

        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(expiresAt)
                .requestedIp(clientIp(request))
                .userAgent(trim(request.getHeader("User-Agent"), 512))
                .build();
        resetTokens.save(token);

        String link = baseUrl + "/auth?mode=reset&token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        emailSender.send(user.getEmail(), "Reset your ContactNexus password", resetEmailHtml(user.getName(), link, ttl));
    }

    /**
     * Consume a reset token: swap the password, mark the row used, revoke every
     * refresh-token family for the user, and send a confirmation email.
     */
    public void completeReset(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadCredentialsException("Invalid or expired reset link");
        }
        PasswordResetToken token = resetTokens.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired reset link"));

        if (token.isUsed() || token.isExpired()) {
            throw new BadCredentialsException("Invalid or expired reset link");
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        token.setUsedAt(Instant.now());
        // Using the emailed link proves the owner has the inbox: confirm the
        // address (this is also how someone reclaims an address another person
        // signed up with) and lift any sign-in lock.
        user.setEmailVerified(true);
        loginAttempts.clear(user);

        // Any lingering session - including anything a thief might be holding -
        // dies here. User must sign in again with the new password.
        refreshTokenService.revokeAllForUser(user.getId());

        sendPasswordChangedEmail(user);

        log.info("Password reset completed for user {}", user.getId());
    }

    /** Also used by the signed-in "change password" flow. */
    public void sendPasswordChangedEmail(User user) {
        emailSender.send(
                user.getEmail(),
                "Your ContactNexus password was changed",
                passwordChangedEmailHtml(user.getName()));
    }

    public void deleteAllForUser(Long userId) {
        resetTokens.deleteAllForUser(userId);
    }

    private static String hash(String raw) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sha.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return trim(fwd.split(",")[0].trim(), 64);
        }
        return trim(request.getRemoteAddr(), 64);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String resetEmailHtml(String name, String link, Duration ttl) {
        long minutes = ttl.toMinutes();
        return """
                <p>Hi %s,</p>
                <p>Someone (hopefully you) asked to reset the password on your ContactNexus account.</p>
                <p><a href="%s" style="display:inline-block;padding:10px 18px;background:#2563eb;color:#fff;text-decoration:none;border-radius:6px">Reset your password</a></p>
                <p>Or paste this link into your browser:<br/><code>%s</code></p>
                <p>The link expires in %d minutes and can only be used once.</p>
                <p>If this wasn't you, you can ignore this email - nothing has changed on your account.</p>
                <p>- ContactNexus</p>
                """.formatted(escape(name), link, link, minutes);
    }

    private static String passwordChangedEmailHtml(String name) {
        return """
                <p>Hi %s,</p>
                <p>Your ContactNexus password was just changed. All existing sessions have been signed out.</p>
                <p>If you didn't make this change, please reset your password immediately and contact support.</p>
                <p>- ContactNexus</p>
                """.formatted(escape(name));
    }

    private static String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
