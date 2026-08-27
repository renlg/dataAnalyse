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
    private static final Set<String> TYPES=Set.of("start","end","condition","taiwei","llm","h2sql","sqlitesql","python");
    private static final Set<String> STATUSES=Set.of("draft","active","disabled");
    private final WorkflowRepository workflows; private final WorkflowNodeRepository nodes; private final WorkflowRunRepository runs; private final ObjectMapper mapper;
    public WorkflowService(WorkflowRepository w,WorkflowNodeRepository n,WorkflowRunRepository r,ObjectMapper m){workflows=w;nodes=n;runs=r;mapper=m;}
    public List<Map<String,Object>> list(){return workflows.findAll().stream().map(this::summary).toList();}
    public Map<String,Object> detail(Long id){WorkflowEntity w=getEntity(id);Map<String,Object> m=new LinkedHashMap<>(summary(w));m.put("description",w.getDescription());m.put("config",workflowConfig(w));m.put("nodes",getNodes(id));return m;}
    @Transactional public Map<String,Object> save(Long id,Map<String,Object> body){WorkflowEntity w=id==null?new WorkflowEntity():getEntity(id);String name=str(body.get("name"));if(name==null||name.isBlank())throw new BusinessException(400,"工作流名称不能为空");w.setName(name);w.setDescription(str(body.get("description")));String status=str(body.get("status"));if(status!=null){if(!STATUSES.contains(status))throw new BusinessException(400,"工作流状态不正确");w.setStatus(status);}Object configObj=body.get("config");if(configObj!=null){String configStr;if(configObj instanceof String s){configStr=s.isBlank()?null:s;}else{try{configStr=mapper.writeValueAsString(configObj);}catch(Exception e){throw new BusinessException(400,"流程配置无法序列化");}}w.setConfig(configStr);if(configStr!=null){try{Map<String,Object> cfg=mapper.readValue(configStr,new TypeReference<>(){});String cron=str(cfg.get("cron"));if(cron!=null&&!cron.isBlank())CronExpression.parse(cron);parseConcurrency(cfg.get("concurrency"));}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(400,"流程配置格式不正确");}}}return summary(workflows.save(w));}
    @Transactional public void delete(Long id){getEntity(id);nodes.deleteByWorkflowId(id);workflows.deleteById(id);}
    @Transactional public List<Map<String,Object>> replaceNodes(Long id,List<Map<String,Object>> body){getEntity(id);Set<String> keys=new HashSet<>();List<WorkflowNodeEntity> saved=new ArrayList<>();
        for(Map<String,Object> item:body){String key=str(item.get("nodeKey")),type=str(item.get("nodeType"));if(key==null||key.isBlank()||!keys.add(key))throw new BusinessException(400,"节点标识不能为空且不能重复");if(!TYPES.contains(type))throw new BusinessException(400,"不支持的节点类型："+type);Map<String,Object> config=map(item.get("config"));
            WorkflowNodeEntity n=new WorkflowNodeEntity();n.setWorkflowId(id);n.setNodeKey(key);n.setNodeType(type);n.setName(Optional.ofNullable(str(item.get("name"))).orElse(type));n.setPositionX(number(item.get("positionX")));n.setPositionY(number(item.get("positionY")));try{n.setConfigJson(mapper.writeValueAsString(config));}catch(Exception e){throw new BusinessException(400,"节点配置无法序列化");}saved.add(n);}
        // JSON 存储：整流程节点写入 workflows.definition，废弃 workflow_nodes 表
        WorkflowEntity w=getEntity(id);w.setDefinition(serializeDefinition(saved));workflows.save(w);return getNodes(id);
    }
    public List<Map<String,Object>> getNodes(Long id){return getNodeEntities(id).stream().map(this::nodeView).toList();}
    public List<WorkflowNodeEntity> getNodeEntities(Long id){WorkflowEntity w=getEntity(id);List<WorkflowNodeEntity> fromDef=parseDefinition(w);if(!fromDef.isEmpty())return fromDef;return nodes.findByWorkflowIdOrderById(id);}
    /** 把节点列表序列化成 definition JSON（config 内含 _outgoing 连线） */
    private String serializeDefinition(List<WorkflowNodeEntity> list){
        try{
            List<Map<String,Object>> arr=new ArrayList<>();
            for(WorkflowNodeEntity n:list){Map<String,Object> m=new LinkedHashMap<>();m.put("nodeKey",n.getNodeKey());m.put("nodeType",n.getNodeType());m.put("name",n.getName());m.put("positionX",n.getPositionX());m.put("positionY",n.getPositionY());m.put("config",parseConfig(n));arr.add(m);}
            Map<String,Object> def=new LinkedHashMap<>();def.put("nodes",arr);return mapper.writeValueAsString(def);
        }catch(Exception e){throw new BusinessException(500,"工作流定义序列化失败");}
    }
    /** 从 definition JSON 解析节点列表；无 definition 返回空 */
    private List<WorkflowNodeEntity> parseDefinition(WorkflowEntity w){
        if(w.getDefinition()==null||w.getDefinition().isBlank())return List.of();
        try{
            Map<String,Object> def=mapper.readValue(w.getDefinition(),new TypeReference<Map<String,Object>>(){});
            Object nodesObj=def.get("nodes");
            if(!(nodesObj instanceof List<?> list))return List.of();
            List<WorkflowNodeEntity> out=new ArrayList<>();
            for(Object o:list){if(!(o instanceof Map<?,?> mm))continue;Map<String,Object> m=(Map<String,Object>)mm;WorkflowNodeEntity n=new WorkflowNodeEntity();n.setWorkflowId(w.getId());n.setNodeKey(str(m.get("nodeKey")));n.setNodeType(str(m.get("nodeType")));n.setName(str(m.get("name")));n.setPositionX(number(m.get("positionX")));n.setPositionY(number(m.get("positionY")));Object cfg=m.get("config");try{n.setConfigJson(cfg==null?"{}":mapper.writeValueAsString(cfg));}catch(Exception ignored){}out.add(n);}
            return out;
        }catch(Exception e){return List.of();}
    }
    public List<Map<String,Object>> getRuns(Long id){getEntity(id);return runs.findByWorkflowIdOrderByStartedAtDesc(id).stream().map(r -> runView(r, false)).toList();}
    public List<Map<String,Object>> listRuns(Long workflowId){
        List<WorkflowRunEntity> all = workflowId==null ? runs.findAllByOrderByStartedAtDesc() : runs.findByWorkflowIdOrderByStartedAtDesc(workflowId);
        return all.stream().map(r -> runView(r, false)).toList();
    }
    public Map<String,Object> getRun(Long id){return runView(runs.findById(id).orElseThrow(()->new BusinessException(404,"运行记录不存在")), true);}
    public WorkflowEntity getEntity(Long id){return workflows.findById(id).orElseThrow(()->new BusinessException(404,"工作流不存在"));}
    public Map<String,Object> parseConfig(WorkflowNodeEntity n){try{return mapper.readValue(Optional.ofNullable(n.getConfigJson()).orElse("{}"),new TypeReference<>(){});}catch(Exception e){throw new BusinessException(500,"节点配置解析失败");}}
    public Map<String,Object> getWorkflowConfig(Long id){return workflowConfig(getEntity(id));}
    public int getWorkflowConcurrency(Long id){return parseConcurrency(parseWorkflowConfig(getEntity(id)).get("concurrency"));}
    private Map<String,Object> workflowConfig(WorkflowEntity w){Map<String,Object> config=parseWorkflowConfig(w);config.putIfAbsent("concurrency",WorkflowExecutorManager.DEFAULT_CONCURRENCY);return config;}
    private Map<String,Object> parseWorkflowConfig(WorkflowEntity w){try{return mapper.readValue(Optional.ofNullable(w.getConfig()).orElse("{}"),new TypeReference<>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private int parseConcurrency(Object value){if(value==null)return WorkflowExecutorManager.DEFAULT_CONCURRENCY;try{int concurrency=Integer.parseInt(String.valueOf(value));if(concurrency<1)throw new BusinessException(400,"工作流并发数必须大于 0");return concurrency;}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(400,"工作流并发数必须是正整数");}}
    private Map<String,Object> summary(WorkflowEntity w){Map<String,Object> m=new LinkedHashMap<>();m.put("id",w.getId());m.put("name",w.getName());m.put("status",w.getStatus());m.put("nodeCount",getNodeEntities(w.getId()).size());m.put("createdAt",w.getCreatedAt());m.put("updatedAt",w.getUpdatedAt());return m;}
    private Map<String,Object> nodeView(WorkflowNodeEntity n){Map<String,Object> m=new LinkedHashMap<>();m.put("id",n.getId());m.put("nodeKey",n.getNodeKey());m.put("nodeType",n.getNodeType());m.put("name",n.getName());m.put("positionX",n.getPositionX());m.put("positionY",n.getPositionY());m.put("config",parseConfig(n));return m;}
    private Map<String,Object> runView(WorkflowRunEntity r, boolean includeDetails){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getId());m.put("workflowId",r.getWorkflowId());m.put("workflowName",workflows.findById(r.getWorkflowId()).map(WorkflowEntity::getName).orElse(null));m.put("status",r.getStatus());m.put("startedAt",r.getStartedAt());m.put("finishedAt",r.getFinishedAt());m.put("logs",r.getLogs());m.put("nodeResults",includeDetails?nodeResultsView(r):null);return m;}
    /** 解析运行时的节点结果(nodeKey->output), 补上节点名/类型, 供前端表格展示 */
    private List<Map<String,Object>> nodeResultsView(WorkflowRunEntity r){
        List<Map<String,Object>> list=new ArrayList<>();
        if(r.getNodeResults()==null||r.getNodeResults().isBlank()) return list;
        try{
            Map<String,WorkflowNodeEntity> nodeMap=getNodeEntities(r.getWorkflowId()).stream().collect(java.util.stream.Collectors.toMap(WorkflowNodeEntity::getNodeKey,(WorkflowNodeEntity n)->n,(a,b)->a));
            @SuppressWarnings("unchecked") Map<String,Object> raw=mapper.readValue(r.getNodeResults(),new TypeReference<Map<String,Object>>(){});
            for(Map.Entry<String,Object> e:raw.entrySet()){
                WorkflowNodeEntity node=nodeMap.get(e.getKey());
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("nodeKey",e.getKey());
                row.put("nodeName",node!=null?node.getName():e.getKey());
                row.put("nodeType",node!=null?node.getNodeType():null);
                row.put("output",e.getValue());
                list.add(row);
            }
        }catch(Exception ignored){}
        return list;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object o){return o instanceof Map<?,?>?(Map<String,Object>)o:new LinkedHashMap<>();}private String str(Object o){return o==null?null:String.valueOf(o);}private Double number(Object o){return o==null?0d:Double.valueOf(String.valueOf(o));}
}
