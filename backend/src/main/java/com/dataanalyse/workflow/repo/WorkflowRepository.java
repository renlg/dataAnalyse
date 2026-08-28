package com.dataanalyse.workflow.repo;
import com.dataanalyse.workflow.entity.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
public interface WorkflowRepository extends JpaRepository<WorkflowEntity,Long> {
    @Query("SELECT w.id, w.name FROM WorkflowEntity w WHERE w.id IN :ids")
    List<Object[]> findNamesByIds(@Param("ids") Collection<Long> ids);
}
