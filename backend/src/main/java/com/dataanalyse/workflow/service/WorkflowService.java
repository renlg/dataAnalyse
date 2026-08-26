package com.dataanalyse.workflow.service;

import com.dataanalyse.common.BusinessException;
import com.dataanalyse.workflow.entity.*;
import com.dataanalyse.workflow.repo.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.support.CronExpression;
import java.util.*;

@Service
public class WorkflowService {
    private static final Set<String> TYPES=Set.of("start","end","taiwei","llm","h2sql","sqlitesql");
    private static final Set<String> STATUSES=Set.of("draft","active","disabled");
    private final WorkflowRepository workflows; private final WorkflowNodeRepository nodes; private final WorkflowRunRepository runs; private final ObjectMapper mapper;
    public WorkflowService(WorkflowRepository w,WorkflowNodeRepository n,WorkflowRunRepository r,ObjectMapper m){workflows=w;nodes=n;runs=r;mapper=m;}
    public List<Map<String,Object>> list(){return workflows.findAll().stream().map(this::summary).toList();}
    public Map<String,Object> detail(Long id){WorkflowEntity w=getEntity(id);Map<String,Object> m=new LinkedHashMap<>(summary(w));m.put("description",w.getDescription());m.put("config",parseWorkflowConfig(w));m.put("nodes",getNodes(id));return m;}
    @Transactional public Map<String,Object> save(Long id,Map<String,Object> body){WorkflowEntity w=id==null?new WorkflowEntity():getEntity(id);String name=str(body.get("name"));if(name==null||name.isBlank())throw new BusinessException(400,"工作流名称不能为空");w.setName(name);w.setDescription(str(body.get("description")));String status=str(body.get("status"));if(status!=null){if(!STATUSES.contains(status))throw new BusinessException(400,"工作流状态不正确");w.setStatus(status);}Object configObj=body.get("config");if(configObj!=null){String configStr;if(configObj instanceof String s){configStr=s.isBlank()?null:s;}else{try{configStr=mapper.writeValueAsString(configObj);}catch(Exception e){throw new BusinessException(400,"流程配置无法序列化");}}w.setConfig(configStr);if(configStr!=null){try{Map<String,Object> cfg=mapper.readValue(configStr,new TypeReference<>(){});String cron=str(cfg.get("cron"));if(cron!=null&&!cron.isBlank())CronExpression.parse(cron);}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(400,"流程配置格式不正确");}}}return summary(workflows.save(w));}
    @Transactional public void delete(Long id){getEntity(id);nodes.deleteByWorkflowId(id);workflows.deleteById(id);}
    @Transactional public List<Map<String,Object>> replaceNodes(Long id,List<Map<String,Object>> body){getEntity(id);Set<String> keys=new HashSet<>();List<WorkflowNodeEntity> saved=new ArrayList<>();
        for(Map<String,Object> item:body){String key=str(item.get("nodeKey")),type=str(item.get("nodeType"));if(key==null||key.isBlank()||!keys.add(key))throw new BusinessException(400,"节点标识不能为空且不能重复");if(!TYPES.contains(type))throw new BusinessException(400,"不支持的节点类型："+type);Map<String,Object> config=map(item.get("config"));
            WorkflowNodeEntity n=new WorkflowNodeEntity();n.setWorkflowId(id);n.setNodeKey(key);n.setNodeType(type);n.setName(Optional.ofNullable(str(item.get("name"))).orElse(type));n.setPositionX(number(item.get("positionX")));n.setPositionY(number(item.get("positionY")));try{n.setConfigJson(mapper.writeValueAsString(config));}catch(Exception e){throw new BusinessException(400,"节点配置无法序列化");}saved.add(n);}
        nodes.deleteByWorkflowId(id);nodes.flush();nodes.saveAll(saved);return getNodes(id);
    }
    public List<Map<String,Object>> getNodes(Long id){getEntity(id);return nodes.findByWorkflowIdOrderById(id).stream().map(this::nodeView).toList();}
    public List<WorkflowNodeEntity> getNodeEntities(Long id){return nodes.findByWorkflowIdOrderById(id);}
    public List<Map<String,Object>> getRuns(Long id){getEntity(id);return runs.findByWorkflowIdOrderByStartedAtDesc(id).stream().map(this::runView).toList();}
    public Map<String,Object> getRun(Long id){return runView(runs.findById(id).orElseThrow(()->new BusinessException(404,"运行记录不存在")));}
    public WorkflowEntity getEntity(Long id){return workflows.findById(id).orElseThrow(()->new BusinessException(404,"工作流不存在"));}
    public Map<String,Object> parseConfig(WorkflowNodeEntity n){try{return mapper.readValue(Optional.ofNullable(n.getConfigJson()).orElse("{}"),new TypeReference<>(){});}catch(Exception e){throw new BusinessException(500,"节点配置解析失败");}}
    public Map<String,Object> getWorkflowConfig(Long id){return parseWorkflowConfig(getEntity(id));}
    private Map<String,Object> parseWorkflowConfig(WorkflowEntity w){try{return mapper.readValue(Optional.ofNullable(w.getConfig()).orElse("{}"),new TypeReference<>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private Map<String,Object> summary(WorkflowEntity w){Map<String,Object> m=new LinkedHashMap<>();m.put("id",w.getId());m.put("name",w.getName());m.put("status",w.getStatus());m.put("nodeCount",w.getId()==null?0:nodes.countByWorkflowId(w.getId()));m.put("createdAt",w.getCreatedAt());m.put("updatedAt",w.getUpdatedAt());return m;}
    private Map<String,Object> nodeView(WorkflowNodeEntity n){Map<String,Object> m=new LinkedHashMap<>();m.put("id",n.getId());m.put("nodeKey",n.getNodeKey());m.put("nodeType",n.getNodeType());m.put("name",n.getName());m.put("positionX",n.getPositionX());m.put("positionY",n.getPositionY());m.put("config",parseConfig(n));return m;}
    private Map<String,Object> runView(WorkflowRunEntity r){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getId());m.put("workflowId",r.getWorkflowId());m.put("status",r.getStatus());m.put("startedAt",r.getStartedAt());m.put("finishedAt",r.getFinishedAt());m.put("logs",r.getLogs());return m;}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object o){return o instanceof Map<?,?>?(Map<String,Object>)o:new LinkedHashMap<>();}private String str(Object o){return o==null?null:String.valueOf(o);}private Double number(Object o){return o==null?0d:Double.valueOf(String.valueOf(o));}
}
