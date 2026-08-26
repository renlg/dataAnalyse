package com.dataanalyse.workflow.repo;
import com.dataanalyse.workflow.entity.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkflowRepository extends JpaRepository<WorkflowEntity,Long> {}
