package com.dataanalyse.apikey.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "api_keys")
public class ApiKeyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, unique=true) private String name;
    @Column(nullable=false) private String type;
    @Column(name="base_url", nullable=false, length=1000) private String baseUrl;
    @Column(name="api_key", nullable=false, length=2000) private String apiKey;
    @Column(length=200) private String model;
    @Column(length=1000) private String remark;
    @Column(name="created_at", insertable=false, updatable=false) private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long id){this.id=id;}
    public String getName(){return name;} public void setName(String name){this.name=name;}
    public String getType(){return type;} public void setType(String type){this.type=type;}
    public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;}
    public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;}
    public String getModel(){return model;} public void setModel(String v){model=v;}
    public String getRemark(){return remark;} public void setRemark(String v){remark=v;}
    public LocalDateTime getCreatedAt(){return createdAt;}
}
