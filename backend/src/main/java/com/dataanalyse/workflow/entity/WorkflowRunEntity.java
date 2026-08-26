package com.dataanalyse.workflow.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="workflow_runs")
public class WorkflowRunEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="workflow_id",nullable=false) private Long workflowId;
    @Column(nullable=false) private String status;
    @Column(name="started_at",nullable=false) private LocalDateTime startedAt;
    @Column(name="finished_at") private LocalDateTime finishedAt;
    @Lob private String logs;
    @Lob @Column(name="node_results") private String nodeResults;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getWorkflowId(){return workflowId;} public void setWorkflowId(Long v){workflowId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;} public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;} public String getLogs(){return logs;} public void setLogs(String v){logs=v;} public String getNodeResults(){return nodeResults;} public void setNodeResults(String v){nodeResults=v;}
}
