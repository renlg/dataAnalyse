package com.dataanalyse.workflow.repo;
import com.dataanalyse.workflow.entity.WorkflowNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface WorkflowNodeRepository extends JpaRepository<WorkflowNodeEntity,Long> {
    List<WorkflowNodeEntity> findByWorkflowIdOrderById(Long workflowId);
    long countByWorkflowId(Long workflowId);
    void deleteByWorkflowId(Long workflowId);
}
