package com.dataanalyse.workflow.service;

import com.dataanalyse.common.BusinessException;
import com.dataanalyse.workflow.entity.*;
import com.dataanalyse.workflow.repo.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.support.CronExpression;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WorkflowService {
    private static final Set<String> TYPES=Set.of("start","end","condition","taiwei","llm","h2sql","sqlitesql","python");
    private static final Set<String> STATUSES=Set.of("draft","active","disabled");
    private static final Pattern FAILED_NODE_PATTERN=Pattern.compile("节点 \\[([^\\]]+)] 执行失败[：:]([\\s\\S]*)");
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
    public List<Map<String,Object>> getRuns(Long id){getEntity(id);List<WorkflowRunEntity> entities=runs.findByWorkflowIdOrderByStartedAtDesc(id);Map<Long,String> nameMap=batchNames(entities.stream().map(WorkflowRunEntity::getWorkflowId).collect(Collectors.toSet()));return entities.stream().map(r -> runView(r, false, nameMap)).toList();}
    public Map<String,Object> listRuns(Long workflowId, int page, int size){
        PageRequest pageRequest=PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"startedAt"));
        Page<WorkflowRunEntity> pageResult=workflowId==null?runs.findAll(pageRequest):runs.findByWorkflowId(workflowId,pageRequest);
        Set<Long> wfIds=pageResult.getContent().stream().map(WorkflowRunEntity::getWorkflowId).collect(Collectors.toSet());
        Map<Long,String> nameMap=batchNames(wfIds);
        List<Map<String,Object>> list=pageResult.getContent().stream().map(r -> runView(r, false, nameMap)).toList();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("list",list);
        result.put("total",pageResult.getTotalElements());
        result.put("page",page);
        result.put("size",size);
        return result;
    }
    public Map<String,Object> getRun(Long id){WorkflowRunEntity r=runs.findById(id).orElseThrow(()->new BusinessException(404,"运行记录不存在"));Map<Long,String> nameMap=batchNames(Set.of(r.getWorkflowId()));return runView(r, true, nameMap);}
    public boolean isMonitorEnabled(Long id){return Boolean.TRUE.equals(parseWorkflowConfig(getEntity(id)).get("monitorEnabled"));}
    public List<Map<String,Object>> getPublicNodes(Long id){List<WorkflowNodeEntity> nodeList=getNodeEntities(id);Map<String,String> nodeNames=new LinkedHashMap<>();for(WorkflowNodeEntity node:nodeList)if(node.getNodeKey()!=null&&node.getName()!=null)nodeNames.putIfAbsent(node.getNodeKey(),node.getName());return nodeList.stream().map(node->{Map<String,Object> item=new LinkedHashMap<>();item.put("nodeKey",node.getNodeKey());item.put("nodeName",node.getName());item.put("nodeType",node.getNodeType());item.put("positionX",node.getPositionX());item.put("positionY",node.getPositionY());item.put("outgoing",getPublicOutgoing(node));item.put("configInfo",getPublicConfigInfo(node,nodeNames));return item;}).toList();}
    /** 提取节点的出边目标 nodeKey 列表，仅用于监控页只读连线展示，不泄漏敏感配置 */
    private List<String> getPublicOutgoing(WorkflowNodeEntity node){Map<String,Object> config=parseConfig(node);Object outgoing=config.get("_outgoing");if(!(outgoing instanceof List<?> list))return List.of();List<String> targets=new ArrayList<>();for(Object value:list){String target=null;if(value instanceof Map<?,?> edge)target=publicText(edge.get("target"));else target=publicText(value);if(target!=null)targets.add(target);}return targets;}
    /** 公开监控仅按节点类型提取展示白名单，禁止透传原始配置中的密钥、地址等字段。 */
    private Map<String,Object> getPublicConfigInfo(WorkflowNodeEntity node,Map<String,String> nodeNames){Map<String,Object> config=parseConfig(node);Map<String,Object> info=new LinkedHashMap<>();switch(node.getNodeType()){
        case "h2sql","sqlitesql" -> info.put("sql",publicText(config.get("sql")));
        case "condition" -> {List<Map<String,Object>> conditions=new ArrayList<>();Object outgoing=config.get("_outgoing");if(outgoing instanceof List<?> list){for(Object value:list){String target=null,condition=null;if(value instanceof Map<?,?> edge){target=publicText(edge.get("target"));condition=publicText(edge.get("condition"));}else target=publicText(value);if(target!=null){Map<String,Object> edgeInfo=new LinkedHashMap<>();edgeInfo.put("target",target);edgeInfo.put("targetName",nodeNames.getOrDefault(target,target));if(condition!=null&&!condition.isBlank())edgeInfo.put("condition",condition);conditions.add(edgeInfo);}}}info.put("conditions",conditions);}
        case "llm" -> {info.put("systemPrompt",publicText(config.get("systemPrompt")));info.put("userPrompt",publicText(config.get("userPrompt")));}
        case "taiwei" -> info.put("prompt",publicText(config.get("prompt")));
        default -> {return null;}
    }return info;}
    public List<Map<String,Object>> getMonitorRuns(Long id,int limit){getEntity(id);if(limit<1||limit>100)throw new BusinessException(400,"执行记录条数必须在 1 到 100 之间");Page<WorkflowRunEntity> page=runs.findByWorkflowId(id,PageRequest.of(0,limit,Sort.by(Sort.Direction.DESC,"startedAt")));return page.getContent().stream().map(this::monitorRunView).toList();}
    public WorkflowEntity getEntity(Long id){return workflows.findById(id).orElseThrow(()->new BusinessException(404,"工作流不存在"));}
    public Map<String,Object> parseConfig(WorkflowNodeEntity n){try{return mapper.readValue(Optional.ofNullable(n.getConfigJson()).orElse("{}"),new TypeReference<>(){});}catch(Exception e){throw new BusinessException(500,"节点配置解析失败");}}
    public Map<String,Object> getWorkflowConfig(Long id){return workflowConfig(getEntity(id));}
    public int getWorkflowConcurrency(Long id){return parseConcurrency(parseWorkflowConfig(getEntity(id)).get("concurrency"));}
    private Map<String,Object> workflowConfig(WorkflowEntity w){Map<String,Object> config=parseWorkflowConfig(w);config.putIfAbsent("concurrency",WorkflowExecutorManager.DEFAULT_CONCURRENCY);return config;}
    private Map<String,Object> parseWorkflowConfig(WorkflowEntity w){try{return mapper.readValue(Optional.ofNullable(w.getConfig()).orElse("{}"),new TypeReference<>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private int parseConcurrency(Object value){if(value==null)return WorkflowExecutorManager.DEFAULT_CONCURRENCY;try{int concurrency=Integer.parseInt(String.valueOf(value));if(concurrency<1)throw new BusinessException(400,"工作流并发数必须大于 0");return concurrency;}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(400,"工作流并发数必须是正整数");}}
    private Map<String,Object> summary(WorkflowEntity w){Map<String,Object> m=new LinkedHashMap<>();m.put("id",w.getId());m.put("name",w.getName());m.put("status",w.getStatus());m.put("nodeCount",getNodeEntities(w.getId()).size());m.put("monitorEnabled",Boolean.TRUE.equals(parseWorkflowConfig(w).get("monitorEnabled")));m.put("createdAt",w.getCreatedAt());m.put("updatedAt",w.getUpdatedAt());return m;}
    private Map<String,Object> nodeView(WorkflowNodeEntity n){Map<String,Object> m=new LinkedHashMap<>();m.put("id",n.getId());m.put("nodeKey",n.getNodeKey());m.put("nodeType",n.getNodeType());m.put("name",n.getName());m.put("positionX",n.getPositionX());m.put("positionY",n.getPositionY());m.put("config",parseConfig(n));return m;}
    private Map<String,Object> runView(WorkflowRunEntity r, boolean includeDetails, Map<Long,String> nameMap){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getId());m.put("workflowId",r.getWorkflowId());m.put("workflowName",nameMap.getOrDefault(r.getWorkflowId(),null));m.put("status",r.getStatus());m.put("startedAt",r.getStartedAt());m.put("finishedAt",r.getFinishedAt());m.put("logs",includeDetails?r.getLogs():null);m.put("nodeResults",includeDetails?nodeResultsView(r):null);return m;}
    private Map<String,Object> monitorRunView(WorkflowRunEntity r){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getId());m.put("status",r.getStatus());m.put("startedAt",r.getStartedAt());m.put("finishedAt",r.getFinishedAt());m.put("nodeResults",nodeResultsView(r));Failure failure=failure(r);m.put("failedNode",failure.failedNode());m.put("error",failure.error());return m;}
    private Failure failure(WorkflowRunEntity run){if(!"failed".equals(run.getStatus()))return new Failure(null,null);String logs=Optional.ofNullable(run.getLogs()).orElse("");Matcher matcher=FAILED_NODE_PATTERN.matcher(logs);if(matcher.find())return new Failure(matcher.group(1),matcher.group(2).trim());return new Failure(null,logs.isBlank()?"工作流执行失败":logs);}
    private Map<Long,String> batchNames(Collection<Long> ids){if(ids==null||ids.isEmpty())return Map.of();return workflows.findNamesByIds(ids).stream().collect(Collectors.toMap(row->(Long)row[0],row->(String)row[1],(a,b)->a));}
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
    private record Failure(String failedNode,String error){}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object o){return o instanceof Map<?,?>?(Map<String,Object>)o:new LinkedHashMap<>();}private String publicText(Object o){return o instanceof String s?s:null;}private String str(Object o){return o==null?null:String.valueOf(o);}private Double number(Object o){return o==null?0d:Double.valueOf(String.valueOf(o));}
}
