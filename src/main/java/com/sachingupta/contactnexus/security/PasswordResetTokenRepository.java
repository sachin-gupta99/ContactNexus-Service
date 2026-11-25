package com.sachingupta.contactnexus.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from PasswordResetToken p where p.expiresAt < :cutoff or p.usedAt is not null")
    int deleteExpiredOrUsed(Instant cutoff);

    @Modifying
    @Query("delete from PasswordResetToken p where p.user.id = :userId")
    int deleteAllForUser(Long userId);
}
