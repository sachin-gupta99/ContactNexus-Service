package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.service.ParameterStoreService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * Resolves the HS256 signing key, preferring an explicitly configured secret,
     * then SSM, then an ephemeral key so local development can always boot.
     */
    @Bean
    public SecretKey jwtSigningKey(
            @Value("${jwt.secret:}") String configuredSecret,
            @Value("${jwt.secret-parameter:}") String parameterName,
            ObjectProvider<ParameterStoreService> parameterStore) {

        if (StringUtils.hasText(configuredSecret)) {
            log.info("JWT signing key loaded from configuration");
            return toKey(configuredSecret);
        }

        if (StringUtils.hasText(parameterName)) {
            ParameterStoreService store = parameterStore.getIfAvailable();
            if (store != null) {
                try {
                    String value = store.getParameterValue(parameterName);
                    log.info("JWT signing key loaded from SSM parameter {}", parameterName);
                    return toKey(value);
                } catch (Exception ex) {
                    log.warn("Could not read JWT secret from SSM parameter {}: {}", parameterName, ex.getMessage());
                }
            }
        }

        log.warn("""
                No JWT secret configured - generating an ephemeral signing key. \
                Every restart will invalidate all issued tokens. \
                Set JWT_SECRET (at least {} characters) or create the SSM parameter {}.""",
                MIN_SECRET_BYTES, parameterName);
        return Jwts.SIG.HS256.key().build();
    }

    private SecretKey toKey(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT secret must be at least %d bytes for HS256, got %d"
                            .formatted(MIN_SECRET_BYTES, bytes.length));
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
