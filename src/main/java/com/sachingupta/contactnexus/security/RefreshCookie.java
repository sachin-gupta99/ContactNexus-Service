package com.sachingupta.contactnexus.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Writes and clears the refresh-token cookie. HttpOnly + path-scoped so JS can't
 * read it and it is only sent to the two auth endpoints that need it.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "cn_refresh";
    private static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final String sameSite;

    public RefreshCookie(
            @Value("${jwt.refresh-cookie-secure:false}") boolean secure,
            @Value("${jwt.refresh-cookie-same-site:Lax}") String sameSite) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public void write(HttpServletResponse response, String rawToken, Duration ttl) {
        ResponseCookie cookie = ResponseCookie.from(NAME, rawToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(ttl)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
