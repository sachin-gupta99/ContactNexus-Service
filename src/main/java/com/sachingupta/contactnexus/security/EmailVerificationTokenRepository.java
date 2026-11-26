package com.sachingupta.contactnexus.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from EmailVerificationToken t where t.expiresAt < :cutoff or t.usedAt is not null")
    int deleteExpiredOrUsed(Instant cutoff);

    @Modifying
    @Query("delete from EmailVerificationToken t where t.user.id = :userId")
    int deleteAllForUser(Long userId);
}
