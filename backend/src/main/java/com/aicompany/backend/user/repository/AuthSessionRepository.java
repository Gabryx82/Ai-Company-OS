package com.aicompany.backend.user.repository;

import com.aicompany.backend.user.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    @Query("select s from AuthSession s join fetch s.user where s.tokenDigest = :digest")
    Optional<AuthSession> findByDigest(@Param("digest") String digest);

    /** Revokes every live session of a user except {@code keep} (which may be null). */
    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null "
            + "and (:keep is null or s.id <> :keep)")
    int revokeAllOf(@Param("userId") Long userId, @Param("keep") Long keep, @Param("now") Instant now);

    @Modifying
    @Query("delete from AuthSession s where s.expiresAt < :before or s.revokedAt < :before")
    int purge(@Param("before") Instant before);
}
