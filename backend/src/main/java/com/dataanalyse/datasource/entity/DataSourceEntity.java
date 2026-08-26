package com.dataanalyse.datasource.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "data_sources")
public class DataSourceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private String name;
    @Column(nullable=false) private String type;
    private String host;
    private Integer port;
    @Column(name="database_name", length=1000) private String databaseName;
    private String username;
    @Column(length=2000) private String password;
    @Column(name="jdbc_url", nullable=false, length=2000) private String jdbcUrl;
    @Column(name="created_at", insertable=false, updatable=false) private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long id){this.id=id;}
    public String getName(){return name;} public void setName(String name){this.name=name;}
    public String getType(){return type;} public void setType(String type){this.type=type;}
    public String getHost(){return host;} public void setHost(String host){this.host=host;}
    public Integer getPort(){return port;} public void setPort(Integer port){this.port=port;}
    public String getDatabaseName(){return databaseName;} public void setDatabaseName(String v){databaseName=v;}
    public String getUsername(){return username;} public void setUsername(String v){username=v;}
    public String getPassword(){return password;} public void setPassword(String v){password=v;}
    public String getJdbcUrl(){return jdbcUrl;} public void setJdbcUrl(String v){jdbcUrl=v;}
    public LocalDateTime getCreatedAt(){return createdAt;}
}
