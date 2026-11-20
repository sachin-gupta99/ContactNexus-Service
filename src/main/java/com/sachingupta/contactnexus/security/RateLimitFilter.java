package com.sachingupta.contactnexus.security;

import io.github.bucket4j.Bandwidth;
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
 * Per-IP rate limiting on the unauthenticated auth endpoints. Uses Bucket4j's
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
 * </ul>
 *
 * <p><strong>Known limitations</strong>:
 * <ul>
 *   <li>Per-IP, not per-account. An attacker rotating IPs over proxies bypasses this;
 *       account-level lockout is a separate concern.</li>
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
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Bandwidth LOGIN_LIMIT  = Bandwidth.simple(10, Duration.ofMinutes(5));
    private static final Bandwidth SIGNUP_LIMIT = Bandwidth.simple(5,  Duration.ofHours(1));
    private static final Bandwidth FORGOT_LIMIT = Bandwidth.simple(3,  Duration.ofHours(1));
    private static final Bandwidth RESET_LIMIT  = Bandwidth.simple(10, Duration.ofHours(1));

    private final Map<String, Bucket> loginBuckets  = new ConcurrentHashMap<>();
    private final Map<String, Bucket> signupBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> forgotBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> resetBuckets  = new ConcurrentHashMap<>();

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
        log.warn("Rate limit tripped for {} on {}", clientIp(request), request.getRequestURI());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.getWriter().write("""
                {"timestamp":"%s","status":429,"error":"Too Many Requests","message":"Too many requests. Try again in %d seconds.","retryAfterSeconds":%d}"""
                .formatted(Instant.now(), retryAfterSeconds, retryAfterSeconds));
    }

    private Bucket bucketFor(HttpServletRequest request) {
        // Only POST requests consume tokens; GETs on the same path (if any) pass through.
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String ip = clientIp(request);
        return switch (request.getRequestURI()) {
            case "/api/v1/auth/login"            -> loginBuckets.computeIfAbsent(ip, k -> bucket(LOGIN_LIMIT));
            case "/api/v1/auth/signup"           -> signupBuckets.computeIfAbsent(ip, k -> bucket(SIGNUP_LIMIT));
            case "/api/v1/auth/forgot-password"  -> forgotBuckets.computeIfAbsent(ip, k -> bucket(FORGOT_LIMIT));
            case "/api/v1/auth/reset-password"   -> resetBuckets.computeIfAbsent(ip, k -> bucket(RESET_LIMIT));
            default -> null;
        };
    }

    private static Bucket bucket(Bandwidth limit) {
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Honours {@code X-Forwarded-For} because requests reach the app through
     * Vercel's edge (proxy) &rarr; Render's edge (proxy) &rarr; the container.
     * Both proxies prepend the previous hop's IP, so the first comma-separated
     * value is the real client.
     */
    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
