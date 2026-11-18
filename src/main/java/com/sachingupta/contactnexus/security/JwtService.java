package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(SecretKey jwtSigningKey, @Value("${jwt.access-ttl:PT15M}") Duration accessTtl) {
        this.key = jwtSigningKey;
        this.accessTtl = accessTtl;
    }

    /**
     * Subject is the email so the auth filter can load the user by username; the
     * id rides along as a claim to save a lookup where the controller already
     * holds the principal.
     */
    public IssuedAccessToken issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTtl);
        String token = Jwts.builder()
                .subject(user.getEmail())
                .claim("uid", user.getId())
                .claim("name", user.getName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedAccessToken(token, expiresAt, accessTtl);
    }

    /**
     * @return the email in the token's subject
     * @throws io.jsonwebtoken.JwtException if the token is malformed, expired or unsigned by us
     */
    public String parseEmail(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public record IssuedAccessToken(String token, Instant expiresAt, Duration ttl) {}
}
