package com.dataanalyse.auth.repo;

import com.dataanalyse.auth.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<UserEntity, Long> {
    java.util.Optional<UserEntity> findByUsername(String username);
}
