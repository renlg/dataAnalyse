package com.dataanalyse.auth.repo;

import com.dataanalyse.auth.entity.AuthTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
public interface AuthTokenRepository extends JpaRepository<AuthTokenEntity, String> {
    @Modifying @Transactional
    @Query("DELETE FROM AuthTokenEntity t WHERE t.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
