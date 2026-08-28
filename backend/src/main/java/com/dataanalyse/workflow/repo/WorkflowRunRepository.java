package com.dataanalyse.workflow.repo;
import com.dataanalyse.workflow.entity.WorkflowRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity,Long> {
    List<WorkflowRunEntity> findByWorkflowIdOrderByStartedAtDesc(Long workflowId);
    List<WorkflowRunEntity> findAllByOrderByStartedAtDesc();
    Page<WorkflowRunEntity> findByWorkflowId(Long workflowId, Pageable pageable);
    Page<WorkflowRunEntity> findAll(Pageable pageable);
    List<WorkflowRunEntity> findByStatusAndStartedAtBefore(String status, LocalDateTime startedAt);
}
