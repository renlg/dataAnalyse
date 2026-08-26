package com.dataanalyse.workflow.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="workflows")
public class WorkflowEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private String name;
    @Column(length=2000) private String description;
    @Column(length=4000) private String config;
    @Lob private String definition;
    @Column(nullable=false) private String status="draft";
    @Column(name="created_at",insertable=false,updatable=false) private LocalDateTime createdAt;
    @Column(name="updated_at") private LocalDateTime updatedAt;
    @PrePersist void create(){updatedAt=LocalDateTime.now();} @PreUpdate void update(){updatedAt=LocalDateTime.now();}
    public Long getId(){return id;} public void setId(Long v){id=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;} public String getConfig(){return config;} public void setConfig(String v){config=v;} public String getDefinition(){return definition;} public void setDefinition(String v){definition=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDateTime getCreatedAt(){return createdAt;} public LocalDateTime getUpdatedAt(){return updatedAt;}
}
