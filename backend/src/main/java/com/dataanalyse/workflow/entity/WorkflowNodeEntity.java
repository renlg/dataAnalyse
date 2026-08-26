package com.dataanalyse.workflow.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="workflow_nodes",uniqueConstraints=@UniqueConstraint(columnNames={"workflow_id","node_key"}))
public class WorkflowNodeEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="workflow_id",nullable=false) private Long workflowId;
    @Column(name="node_key",nullable=false) private String nodeKey;
    @Column(name="node_type",nullable=false) private String nodeType;
    @Column(nullable=false) private String name;
    @Column(name="position_x",nullable=false) private Double positionX;
    @Column(name="position_y",nullable=false) private Double positionY;
    @Lob @Column(name="config_json") private String configJson;
    @Column(name="created_at",insertable=false,updatable=false) private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getWorkflowId(){return workflowId;} public void setWorkflowId(Long v){workflowId=v;} public String getNodeKey(){return nodeKey;} public void setNodeKey(String v){nodeKey=v;} public String getNodeType(){return nodeType;} public void setNodeType(String v){nodeType=v;} public String getName(){return name;} public void setName(String v){name=v;} public Double getPositionX(){return positionX;} public void setPositionX(Double v){positionX=v;} public Double getPositionY(){return positionY;} public void setPositionY(Double v){positionY=v;} public String getConfigJson(){return configJson;} public void setConfigJson(String v){configJson=v;} public LocalDateTime getCreatedAt(){return createdAt;}
}
