package com.sachingupta.contactnexus.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * Works out which IP a request really came from, for rate limiting and for the
 * IP shown on signed-in devices.
 *
 * <p>{@code X-Forwarded-For} can't be trusted on its own: Render <em>appends</em>
 * to whatever chain the caller sent, so anyone hitting the Render URL directly
 * controls the left-most value. Vercel, on the other hand, overwrites the header
 * with the real visitor IP on the requests it proxies. So:
 * <ol>
 *   <li>Requests that carry the shared secret Vercel adds on the {@code /api}
 *       rewrite ({@value #PROXY_SECRET_HEADER}) came through Vercel, and the
 *       left-most {@code X-Forwarded-For} is the visitor.</li>
 *   <li>Anything else bypassed Vercel: use the header the edge in front of this
 *       service sets itself (Render: {@code CF-Connecting-IP}), and never
 *       {@code X-Forwarded-For}.</li>
 *   <li>Otherwise the socket address (local development).</li>
 * </ol>
 * If no secret is configured the old behaviour (left-most value) is kept, with a
 * warning, so a deploy that is missing the secret can't put every Vercel user
 * behind one shared rate-limit bucket.
 */
@Component
public class ClientIpResolver {

    public static final String PROXY_SECRET_HEADER = "X-Origin-Secret";

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final byte[] proxySecret;
    private final String edgeHeader;

    public ClientIpResolver(@Value("${app.client-ip.proxy-secret:}") String proxySecret,
                            @Value("${app.client-ip.edge-header:}") String edgeHeader) {
        this.proxySecret = proxySecret == null || proxySecret.isBlank()
                ? null
                : proxySecret.getBytes(StandardCharsets.UTF_8);
        this.edgeHeader = edgeHeader == null ? "" : edgeHeader.trim();
        if (this.proxySecret == null) {
            log.warn("app.client-ip.proxy-secret is not set: trusting the left-most X-Forwarded-For value, "
                    + "which callers that bypass the proxy can forge. Set PROXY_SECRET here and ORIGIN_SECRET on Vercel.");
        }
    }

    public String resolve(HttpServletRequest request) {
        if (proxySecret == null) {
            String first = firstForwarded(request);
            return first != null ? first : request.getRemoteAddr();
        }
        if (fromTrustedProxy(request)) {
            String first = firstForwarded(request);
            if (first != null) return first;
        }
        if (!edgeHeader.isEmpty()) {
            String edge = valid(request.getHeader(edgeHeader));
            if (edge != null) return edge;
        }
        return request.getRemoteAddr();
    }

    /** Constant-time comparison so the secret can't be guessed byte by byte from timings. */
    boolean fromTrustedProxy(HttpServletRequest request) {
        String supplied = request.getHeader(PROXY_SECRET_HEADER);
        return supplied != null && MessageDigest.isEqual(proxySecret, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String firstForwarded(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd == null || fwd.isBlank()) return null;
        return valid(fwd.split(",")[0]);
    }

    /** Only plausible IPv4/IPv6 literals, so a header can't inject arbitrary text into logs or the database. */
    private static String valid(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return IP.matcher(v).matches() ? v : null;
    }
}
