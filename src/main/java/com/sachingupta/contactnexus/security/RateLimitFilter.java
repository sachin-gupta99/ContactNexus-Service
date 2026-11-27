package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.common.ClientIpResolver;
import io.github.bucket4j.Bandwidth;
import lombok.RequiredArgsConstructor;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiting on the auth endpoints and other expensive writes. Uses Bucket4j's
 * token-bucket algorithm with in-memory maps - fine for a single Render instance.
 *
 * <p>Limits chosen to tolerate real-user retries while blunting brute-force and
 * email-quota abuse:
 *
 * <ul>
 *   <li>{@code /login}            - 10 per 5 min (allows mistyping, slows brute force)</li>
 *   <li>{@code /signup}           - 5 per hour  (caps mass account creation)</li>
 *   <li>{@code /forgot-password}  - 3 per hour  (protects Brevo email quota)</li>
 *   <li>{@code /reset-password}   - 10 per hour (defence against token brute force)</li>
 *   <li>{@code POST /change-password} and {@code DELETE /users/me} - 10 per 15 min,
 *       shared (both re-check the password, so a stolen access token can't be used
 *       to brute-force it)</li>
 *   <li>{@code /verify-email}     - 10 per hour (defence against token brute force)</li>
 *   <li>{@code /resend-verification} - 3 per hour (protects Brevo email quota)</li>
 *   <li>{@code GET /public/**}   - 60 per minute (public card links; blunts token guessing)</li>
 *   <li>{@code POST /internal/jobs/**} - 10 per hour</li>
 *   <li>{@code /contacts/import}  - 20 per hour (each call can insert up to 500 rows)</li>
 * </ul>
 *
 * <p><strong>Known limitations</strong>:
 * <ul>
 *   <li>Per-IP, not per-account. An attacker rotating IPs over proxies bypasses this;
 *       {@code LoginAttemptService} adds per-account lockout. The IP comes from
 *       {@link ClientIpResolver}, which ignores forged {@code X-Forwarded-For} values.</li>
 *   <li>Maps grow with unique IPs. ~200 B per bucket &rarr; 50k IPs ~= 10 MB, fine for
 *       MVP. When it matters, swap {@link ConcurrentHashMap} for a Caffeine cache with
 *       {@code expireAfterAccess(1h)}.</li>
 *   <li>Cold-start reset. Scale-to-zero on Render wipes the maps; attacker who times
 *       their flood right after a cold start gets a fresh bucket. Mitigation is to
 *       back buckets with Redis.</li>
 * </ul>
 *
 * <p>Runs after CORS (which is at {@link Ordered#HIGHEST_PRECEDENCE}) so that 429
 * responses still carry CORS headers and the browser does not silently drop them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ClientIpResolver clientIps;

    private static final Bandwidth LOGIN_LIMIT  = Bandwidth.simple(10, Duration.ofMinutes(5));
    private static final Bandwidth SIGNUP_LIMIT = Bandwidth.simple(5,  Duration.ofHours(1));
    private static final Bandwidth FORGOT_LIMIT = Bandwidth.simple(3,  Duration.ofHours(1));
    private static final Bandwidth RESET_LIMIT  = Bandwidth.simple(10, Duration.ofHours(1));
    private static final Bandwidth PASSWORD_CHECK_LIMIT = Bandwidth.simple(10, Duration.ofMinutes(15));
    private static final Bandwidth VERIFY_LIMIT = Bandwidth.simple(10, Duration.ofHours(1));
    private static final Bandwidth RESEND_LIMIT = Bandwidth.simple(3, Duration.ofHours(1));
    private static final Bandwidth PUBLIC_CARD_LIMIT = Bandwidth.simple(60, Duration.ofMinutes(1));
    private static final Bandwidth JOBS_LIMIT = Bandwidth.simple(10, Duration.ofHours(1));
    private static final Bandwidth IMPORT_LIMIT = Bandwidth.simple(20, Duration.ofHours(1));

    private final Map<String, Bucket> loginBuckets  = new ConcurrentHashMap<>();
    private final Map<String, Bucket> signupBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> forgotBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> resetBuckets  = new ConcurrentHashMap<>();
    private final Map<String, Bucket> passwordCheckBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> verifyBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> resendBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> publicCardBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> jobsBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> importBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Bucket bucket = bucketFor(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        log.warn("Rate limit tripped for {} on {}", clientIps.resolve(request), request.getRequestURI());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.getWriter().write("""
                {"timestamp":"%s","status":429,"error":"Too Many Requests","message":"Too many requests. Try again in %d seconds.","retryAfterSeconds":%d}"""
                .formatted(Instant.now(), retryAfterSeconds, retryAfterSeconds));
    }

    private Bucket bucketFor(HttpServletRequest request) {
        String ip = clientIps.resolve(request);
        String uri = request.getRequestURI();
        // Public card lookups: enough for real visitors, too few to enumerate tokens.
        if ("GET".equalsIgnoreCase(request.getMethod()) && uri.startsWith("/api/v1/public/")) {
            return publicCardBuckets.computeIfAbsent(ip, k -> bucket(PUBLIC_CARD_LIMIT));
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && uri.startsWith("/api/v1/internal/jobs/")) {
            return jobsBuckets.computeIfAbsent(ip, k -> bucket(JOBS_LIMIT));
        }
        if ("DELETE".equalsIgnoreCase(request.getMethod()) && "/api/v1/users/me".equals(request.getRequestURI())) {
            return passwordCheckBuckets.computeIfAbsent(ip, k -> bucket(PASSWORD_CHECK_LIMIT));
        }
        // Otherwise only POST requests consume tokens; GETs on the same path (if any) pass through.
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        return switch (request.getRequestURI()) {
            case "/api/v1/auth/login"            -> loginBuckets.computeIfAbsent(ip, k -> bucket(LOGIN_LIMIT));
            case "/api/v1/auth/signup"           -> signupBuckets.computeIfAbsent(ip, k -> bucket(SIGNUP_LIMIT));
            case "/api/v1/auth/forgot-password"  -> forgotBuckets.computeIfAbsent(ip, k -> bucket(FORGOT_LIMIT));
            case "/api/v1/auth/reset-password"   -> resetBuckets.computeIfAbsent(ip, k -> bucket(RESET_LIMIT));
            case "/api/v1/auth/change-password"  -> passwordCheckBuckets.computeIfAbsent(ip, k -> bucket(PASSWORD_CHECK_LIMIT));
            case "/api/v1/auth/verify-email"     -> verifyBuckets.computeIfAbsent(ip, k -> bucket(VERIFY_LIMIT));
            case "/api/v1/auth/resend-verification" -> resendBuckets.computeIfAbsent(ip, k -> bucket(RESEND_LIMIT));
            case "/api/v1/contacts/import"       -> importBuckets.computeIfAbsent(ip, k -> bucket(IMPORT_LIMIT));
            default -> null;
        };
    }

    private static Bucket bucket(Bandwidth limit) {
        return Bucket.builder().addLimit(limit).build();
    }

}
