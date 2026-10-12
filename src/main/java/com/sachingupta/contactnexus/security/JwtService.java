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
    private final Duration ttl;

    public JwtService(SecretKey jwtSigningKey, @Value("${jwt.ttl:PT24H}") Duration ttl) {
        this.key = jwtSigningKey;
        this.ttl = ttl;
    }

    /**
     * Subject is the email because the UI decodes it client-side to identify the
     * signed-in user; the id rides along as a claim to save a lookup.
     */
    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("uid", user.getId())
                .claim("name", user.getName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
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
}
