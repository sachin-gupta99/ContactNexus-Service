package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.email.EmailSender;
import com.sachingupta.contactnexus.user.User;
import com.sachingupta.contactnexus.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/**
 * Proves the person signing up owns the address. Same token scheme as password
 * reset: 32 random bytes in the link, SHA-256 in the database, single use.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EmailVerificationService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationTokenRepository tokens;
    private final UserRepository users;
    private final EmailSender emailSender;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.email-verification.ttl:PT24H}")
    private Duration ttl;

    /** Creates a token and emails the link once the surrounding transaction commits. */
    public void issueFor(User user) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(EmailVerificationToken.builder()
                .user(user)
                .tokenHash(hash(raw))
                .expiresAt(Instant.now().plus(ttl))
                .build());

        String link = baseUrl + "/auth?mode=verify&token=" + URLEncoder.encode(raw, StandardCharsets.UTF_8);
        String to = user.getEmail();
        String html = verificationEmailHtml(user.getName(), link, ttl);
        AfterCommit.run(() -> emailSender.send(to, "Confirm your ContactNexus email", html));
    }

    /**
     * Spends the token and marks the address verified.
     * @return the now-verified user, so the caller can sign them in
     */
    public User verify(String rawToken) {
        EmailVerificationToken token = (rawToken == null || rawToken.isBlank())
                ? null
                : tokens.findByTokenHash(hash(rawToken)).orElse(null);
        if (token == null || !token.isUsable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This confirmation link is invalid or has expired. Request a new one below.");
        }
        token.setUsedAt(Instant.now());
        User user = token.getUser();
        user.setEmailVerified(true);
        return user;
    }

    /** Enumeration-safe: silently does nothing unless the address has an unverified account. */
    public void resend(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        users.findByEmailIgnoreCase(rawEmail.trim().toLowerCase(Locale.ROOT))
                .filter(u -> !u.isEmailVerified())
                .ifPresent(this::issueFor);
    }

    public void deleteAllForUser(Long userId) {
        tokens.deleteAllForUser(userId);
    }

    public int purgeExpiredOrUsed(Instant now) {
        return tokens.deleteExpiredOrUsed(now);
    }

    static String hash(String raw) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sha.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String verificationEmailHtml(String name, String link, Duration ttl) {
        return """
                <p>Hi %s,</p>
                <p>Confirm this is your email address to finish setting up your ContactNexus account.</p>
                <p><a href="%s" style="display:inline-block;padding:10px 18px;background:#2563eb;color:#fff;text-decoration:none;border-radius:6px">Confirm my email</a></p>
                <p>Or paste this link into your browser:<br/><code>%s</code></p>
                <p>The link expires in %d hours. If you did not create an account, ignore this email and nothing will happen.</p>
                <p>- ContactNexus</p>
                """.formatted(Html.escape(name), link, link, ttl.toHours());
    }
}
