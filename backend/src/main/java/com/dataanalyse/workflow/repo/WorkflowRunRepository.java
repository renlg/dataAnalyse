package com.dataanalyse.workflow.repo;
import com.dataanalyse.workflow.entity.WorkflowRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity,Long> {
    List<WorkflowRunEntity> findByWorkflowIdOrderByStartedAtDesc(Long workflowId);
    List<WorkflowRunEntity> findAllByOrderByStartedAtDesc();
}
