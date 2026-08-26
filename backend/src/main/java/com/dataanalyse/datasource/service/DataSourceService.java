package com.dataanalyse.datasource.service;

import com.dataanalyse.common.BusinessException;
import com.dataanalyse.datasource.entity.DataSourceEntity;
import com.dataanalyse.datasource.repo.DataSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class DataSourceService {
    private final DataSourceRepository repository; private final PasswordCipher cipher; private final JdbcExecutor jdbc;
    public DataSourceService(DataSourceRepository r, PasswordCipher c, JdbcExecutor j){repository=r;cipher=c;jdbc=j;}
    public String buildJdbcUrl(String type, String host, Integer port, String database) {
        if (type==null || !Set.of("sqlite","h2","mysql").contains(type)) throw new BusinessException(400,"不支持的数据源类型");
        if (database==null || database.isBlank()) throw new BusinessException(400,"数据库名称或路径不能为空");
        return switch(type){
            case "sqlite" -> "jdbc:sqlite:"+database;
            case "h2" -> database.startsWith("mem:") ? "jdbc:h2:"+database : (database.startsWith("jdbc:h2:") ? database : "jdbc:h2:file:"+database);
            default -> "jdbc:mysql://"+(host==null||host.isBlank()?"localhost":host)+":"+(port==null?3306:port)+"/"+database+"?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        };
    }
    @Transactional public Map<String,Object> save(Long id, Map<String,Object> body){
        String name=str(body.get("name")); String type=str(body.get("type")); if(name==null||name.isBlank()) throw new BusinessException(400,"数据源名称不能为空");
        DataSourceEntity e=id==null?new DataSourceEntity():getEntity(id); e.setName(name); e.setType(type); e.setHost(str(body.get("host"))); e.setPort(integer(body.get("port"))); e.setDatabaseName(str(body.get("databaseName"))); e.setUsername(str(body.get("username")));
        String raw=str(body.get("password")); if(raw!=null&&!raw.isBlank()&&!"***".equals(raw)) e.setPassword(cipher.encrypt(raw));
        e.setJdbcUrl(buildJdbcUrl(type,e.getHost(),e.getPort(),e.getDatabaseName())); return view(repository.save(e),null);
    }
    public List<Map<String,Object>> list(){ return repository.findAll().stream().map(e->view(e,jdbc.test(e))).toList(); }
    public Map<String,Object> get(Long id){return view(getEntity(id),null);}
    @Transactional public void delete(Long id){ if(!repository.existsById(id)) throw new BusinessException(404,"数据源不存在"); repository.deleteById(id); }
    public boolean test(Long id){return jdbc.test(getEntity(id));}
    public Map<String,Object> query(Long id,String sql){return jdbc.query(getEntity(id),sql);}
    public Map<String,Object> queryForWorkflow(Long id,String expectedType,String sql){ DataSourceEntity e=getEntity(id); if(!expectedType.equals(e.getType())) throw new BusinessException(400,"SQL 节点的数据源类型不匹配"); return jdbc.query(e,sql); }
    public DataSourceEntity getEntity(Long id){return repository.findById(id).orElseThrow(()->new BusinessException(404,"数据源不存在"));}
    private Map<String,Object> view(DataSourceEntity e,Boolean online){ Map<String,Object> m=new LinkedHashMap<>(); m.put("id",e.getId());m.put("name",e.getName());m.put("type",e.getType());m.put("host",e.getHost());m.put("port",e.getPort());m.put("databaseName",e.getDatabaseName());m.put("username",e.getUsername());m.put("password","***");m.put("jdbcUrl",e.getJdbcUrl());m.put("createdAt",e.getCreatedAt());if(online!=null)m.put("online",online);return m; }
    private String str(Object o){return o==null?null:String.valueOf(o);} private Integer integer(Object o){if(o==null||String.valueOf(o).isBlank())return null;return Integer.valueOf(String.valueOf(o));}
}
