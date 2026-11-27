package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.common.ClientIpResolver;
import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.user.User;
import com.sachingupta.contactnexus.user.dto.SessionResponse;
import com.sachingupta.contactnexus.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 48;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final ClientIpResolver clientIps;

    @Value("${jwt.refresh-ttl:P7D}")
    private Duration ttl;

    /** Issue a brand-new refresh token (new family) for a successful login/signup. */
    public IssuedRefreshToken issueFor(User user, HttpServletRequest request) {
        return issue(user, UUID.randomUUID(), request);
    }

    /**
     * Rotate: validate the incoming token, mark it replaced, issue a new one in the
     * same family. If the incoming token is already revoked, treat it as reuse and
     * revoke the whole family.
     *
     * <p>{@code noRollbackFor} is critical: the reuse-detection branch deliberately
     * writes {@code revokeFamily} and then throws {@link BadCredentialsException}
     * to produce a 401. Without this, the default rollback semantics of
     * {@code @Transactional} would undo the family revocation, leaving the
     * attacker's rotated tokens intact.
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public RotationResult rotate(String rawToken, HttpServletRequest request) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadCredentialsException("Missing refresh token");
        }
        String hash = hash(rawToken);
        RefreshToken existing = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (existing.isRevoked()) {
            // Replay of an already-rotated token => likely theft. Blow up the whole family.
            log.warn("Refresh token reuse detected for user {} (family {}); revoking family",
                    existing.getUser().getId(), existing.getFamilyId());
            refreshTokens.revokeFamily(existing.getFamilyId(), Instant.now());
            throw new BadCredentialsException("Refresh token no longer valid");
        }

        if (!Instant.now().isBefore(existing.getExpiresAt())) {
            throw new BadCredentialsException("Refresh token expired");
        }

        User user = existing.getUser();
        IssuedRefreshToken next = issue(user, existing.getFamilyId(), request);

        existing.setRevokedAt(Instant.now());
        existing.setReplacedByHash(hash(next.rawToken()));

        return new RotationResult(user, next);
    }

    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(hash(rawToken))
                .filter(t -> t.getRevokedAt() == null)
                .ifPresent(t -> t.setRevokedAt(Instant.now()));
    }

    public void revokeAllForUser(Long userId) {
        refreshTokens.revokeAllForUser(userId, Instant.now());
    }

    public void deleteAllForUser(Long userId) {
        refreshTokens.deleteAllForUser(userId);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(Long userId, String currentRawToken) {
        Instant now = Instant.now();
        UUID currentFamily = familyOf(userId, currentRawToken);
        Map<UUID, Instant> startedAt = new HashMap<>();
        for (Object[] row : refreshTokens.familyStartTimes(userId)) {
            startedAt.put((UUID) row[0], (Instant) row[1]);
        }
        return refreshTokens.findActiveForUser(userId, now).stream()
                .map(t -> new SessionResponse(
                        t.getFamilyId(),
                        t.getUserAgent(),
                        t.getIpAddress(),
                        startedAt.getOrDefault(t.getFamilyId(), t.getCreatedAt()),
                        t.getCreatedAt(),
                        t.getExpiresAt(),
                        t.getFamilyId().equals(currentFamily)))
                .toList();
    }

    /**
     * Signs one device out.
     * @return true if it was the caller's own device, so the cookie should be cleared
     */
    public boolean revokeSession(Long userId, UUID familyId, String currentRawToken) {
        int revoked = refreshTokens.revokeFamilyForUser(userId, familyId, Instant.now());
        if (revoked == 0) {
            throw new NotFoundException("Session not found");
        }
        return familyId.equals(familyOf(userId, currentRawToken));
    }

    private UUID familyOf(Long userId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return refreshTokens.findByTokenHash(hash(rawToken))
                .filter(t -> t.getUser().getId().equals(userId))
                .map(RefreshToken::getFamilyId)
                .orElse(null);
    }

    private IssuedRefreshToken issue(User user, UUID familyId, HttpServletRequest request) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(ttl);

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(raw))
                .familyId(familyId)
                .expiresAt(expiresAt)
                .userAgent(trim(request.getHeader("User-Agent"), 512))
                .ipAddress(clientIps.resolve(request))
                .build();
        refreshTokens.save(token);

        return new IssuedRefreshToken(raw, expiresAt, ttl);
    }

    private static String hash(String raw) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            // SHA-256 is guaranteed to be present in every Java runtime.
            throw new IllegalStateException(e);
        }
    }


    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record IssuedRefreshToken(String rawToken, Instant expiresAt, Duration ttl) {}

    public record RotationResult(User user, IssuedRefreshToken refreshToken) {}
}
