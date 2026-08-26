package com.dataanalyse.apikey.repo;
import com.dataanalyse.apikey.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {}
