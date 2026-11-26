package com.sachingupta.contactnexus.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now where r.familyId = :familyId and r.revokedAt is null")
    int revokeFamily(UUID familyId, Instant now);

    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now where r.user.id = :userId and r.revokedAt is null")
    int revokeAllForUser(Long userId, Instant now);

    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);

    @Modifying
    @Query("delete from RefreshToken r where r.user.id = :userId")
    int deleteAllForUser(Long userId);

    /** The newest token of each live family: one row per signed-in device. */
    @Query("""
            select r from RefreshToken r
            where r.user.id = :userId and r.revokedAt is null and r.expiresAt > :now
            order by r.createdAt desc
            """)
    List<RefreshToken> findActiveForUser(Long userId, Instant now);

    /** When each family was started, i.e. when that device signed in. */
    @Query("select r.familyId, min(r.createdAt) from RefreshToken r where r.user.id = :userId group by r.familyId")
    List<Object[]> familyStartTimes(Long userId);

    /** Scoped to the user so one account can never revoke another's session. */
    @Modifying
    @Query("""
            update RefreshToken r set r.revokedAt = :now
            where r.user.id = :userId and r.familyId = :familyId and r.revokedAt is null
            """)
    int revokeFamilyForUser(Long userId, UUID familyId, Instant now);
}
