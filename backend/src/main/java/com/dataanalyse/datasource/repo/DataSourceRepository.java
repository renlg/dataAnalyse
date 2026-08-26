package com.dataanalyse.datasource.repo;
import com.dataanalyse.datasource.entity.DataSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DataSourceRepository extends JpaRepository<DataSourceEntity, Long> {}
