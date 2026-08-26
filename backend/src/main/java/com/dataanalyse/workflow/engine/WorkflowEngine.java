package com.dataanalyse.workflow.engine;

import com.dataanalyse.apikey.entity.ApiKeyEntity;
import com.dataanalyse.apikey.service.ApiKeyService;
import com.dataanalyse.common.BusinessException;
import com.dataanalyse.datasource.service.DataSourceService;
import com.dataanalyse.datasource.service.PasswordCipher;
import com.dataanalyse.llm.LlmClient;
import com.dataanalyse.workflow.entity.WorkflowNodeEntity;
import com.dataanalyse.workflow.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WorkflowEngine {
    private final WorkflowService workflowService; private final DataSourceService dataSources; private final LlmClient llm; private final ObjectMapper mapper; private final ApiKeyService apiKeyService; private final PasswordCipher cipher;
    public WorkflowEngine(WorkflowService w,DataSourceService d,LlmClient l,ObjectMapper m,ApiKeyService a,PasswordCipher c){workflowService=w;dataSources=d;llm=l;mapper=m;apiKeyService=a;cipher=c;}
    public ExecutionResult execute(List<WorkflowNodeEntity> nodes,Object trigger){return execute(nodes,trigger,Map.of());}
    public ExecutionResult execute(List<WorkflowNodeEntity> nodes,Object trigger,Map<String,Object> contextParams){
        if(nodes.isEmpty())throw new BusinessException(400,"工作流没有节点");Map<String,WorkflowNodeEntity> byKey=new LinkedHashMap<>();Map<String,Integer> indegree=new HashMap<>();Map<String,List<String>> outgoing=new HashMap<>();
        for(WorkflowNodeEntity n:nodes){byKey.put(n.getNodeKey(),n);indegree.put(n.getNodeKey(),0);}
        for(WorkflowNodeEntity n:nodes){for(String target:outgoing(workflowService.parseConfig(n))){if(!byKey.containsKey(target))throw new BusinessException(400,"连线目标节点不存在："+target);outgoing.computeIfAbsent(n.getNodeKey(),k->new ArrayList<>()).add(target);indegree.put(target,indegree.get(target)+1);}}
        Deque<String> ready=new ArrayDeque<>();indegree.forEach((k,v)->{if(v==0)ready.add(k);});Map<String,Object> results=new LinkedHashMap<>();
        // 节点名称→输出 的共享上下文，下游节点可用 ${节点名称} 引用任意前序节点输出
        Map<String,Object> nodeContext=new LinkedHashMap<>();
        List<String> logs=new ArrayList<>();int visited=0;
        while(!ready.isEmpty()){String key=ready.remove();WorkflowNodeEntity node=byKey.get(key);List<Object> inputs=predecessorInputs(key,outgoing,results);Object input=inputs.size()==1?inputs.get(0):inputs;Object output=executeNode(node,input,trigger,contextParams,nodeContext);results.put(key,output);nodeContext.put(node.getName(),output);logs.add("节点 ["+node.getName()+"] 执行成功："+shortText(output));visited++;for(String next:outgoing.getOrDefault(key,List.of())){int left=indegree.compute(next,(k,v)->v-1);if(left==0)ready.add(next);}}
        if(visited!=nodes.size())throw new BusinessException(400,"工作流存在循环，无法执行");Object overall=null;for(WorkflowNodeEntity n:nodes)if("end".equals(n.getNodeType()))overall=results.get(n.getNodeKey());if(overall==null&&!results.isEmpty())overall=new ArrayList<>(results.values()).get(results.size()-1);return new ExecutionResult(overall,String.join("\n",logs),results);
    }
    private Object executeNode(WorkflowNodeEntity node,Object input,Object trigger,Map<String,Object> contextParams,Map<String,Object> nodeContext){Map<String,Object> c=workflowService.parseConfig(node);String inputText=toText(input);return switch(node.getNodeType()){
        case "start" -> trigger;
        case "end" -> {String output=str(c.get("output"));yield output==null||output.isBlank()?input:render(output,inputText,contextParams,nodeContext);}
        case "taiwei" -> {Map<String,Object> rc=resolveLlmConfig(c);yield llm.chat(str(rc.get("baseUrl")),str(rc.get("apiKey")),str(rc.get("model")),List.of(Map.of("role","system","content",render(str(c.get("prompt")),inputText,contextParams,nodeContext))));}
        case "llm" -> {Map<String,Object> rc=resolveLlmConfig(c);yield llm.chat(str(rc.get("baseUrl")),str(rc.get("apiKey")),str(rc.get("model")),List.of(Map.of("role","system","content",render(str(c.get("systemPrompt")),inputText,contextParams,nodeContext)),Map.of("role","user","content",render(str(c.get("userPrompt")),inputText,contextParams,nodeContext))));}
        case "h2sql","sqlitesql" -> {Object ds=c.get("dataSourceId");if(ds==null)throw new BusinessException(400,"SQL 节点未选择数据源");yield dataSources.queryForWorkflow(Long.valueOf(String.valueOf(ds)),"h2sql".equals(node.getNodeType())?"h2":"sqlite",render(str(c.get("sql")),inputText,contextParams,nodeContext));}
        default -> throw new BusinessException(400,"未知节点类型");};}
    private Map<String,Object> resolveLlmConfig(Map<String,Object> c){
        String baseUrl=str(c.get("baseUrl")); String apiKey=str(c.get("apiKey")); String model=str(c.get("model"));
        Object apiKeyIdObj=c.get("apiKeyId");
        if(apiKeyIdObj!=null&&!String.valueOf(apiKeyIdObj).isBlank()){
            try{
                ApiKeyEntity k=apiKeyService.getEntity(Long.valueOf(String.valueOf(apiKeyIdObj)));
                String savedBaseUrl=k.getBaseUrl(); String savedApiKey=cipher.decrypt(k.getApiKey()); String savedModel=k.getModel();
                if(baseUrl==null||baseUrl.isBlank()) baseUrl=savedBaseUrl;
                if(apiKey==null||apiKey.isBlank()||"***".equals(apiKey)) apiKey=savedApiKey;
                if(model==null||model.isBlank()) model=savedModel;
            }catch(Exception ignored){}
        }
        Map<String,Object> r=new HashMap<>();r.put("baseUrl",baseUrl);r.put("apiKey",apiKey);r.put("model",model);return r;
    }
    private List<Object> predecessorInputs(String target,Map<String,List<String>> outgoing,Map<String,Object> results){List<Object> values=new ArrayList<>();outgoing.forEach((source,targets)->{if(targets.contains(target)&&results.containsKey(source))values.add(results.get(source));});return values;}
    @SuppressWarnings("unchecked") private List<String> outgoing(Map<String,Object> c){Object value=c.get("_outgoing");if(!(value instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
    // 模板替换：先处理 {{input}}/{{prev.output}}，再处理 ${...}（节点名称优先于流程参数，找不到则保持原样）
    private String render(String template,String input,Map<String,Object> params,Map<String,Object> nodeContext){
        if(template==null)return "";
        String r=template.replace("{{input}}",input).replace("{{prev.output}}",input);
        // 扫描所有 ${...} 占位符，按名称查找：先节点上下文，再流程参数
        int start=0;
        while(start<r.length()){
            int idx=r.indexOf("${",start);
            if(idx<0)break;
            int end=r.indexOf("}",idx+2);
            if(end<0)break;
            String name=r.substring(idx+2,end);
            String val=null;
            // 节点名称优先
            if(nodeContext!=null&&nodeContext.containsKey(name)){
                val=nodeContext.get(name)==null?"":toText(nodeContext.get(name));
            }else if(params!=null&&params.containsKey(name)){
                val=params.get(name)==null?"":String.valueOf(params.get(name));
            }
            if(val!=null){
                r=r.substring(0,idx)+val+r.substring(end+1);
                start=idx+val.length();
            }else{
                start=end+1;
            }
        }
        return r;
    }
    private String toText(Object value){if(value==null)return "";if(value instanceof String s)return s;try{return mapper.writeValueAsString(value);}catch(Exception e){return String.valueOf(value);}}
    private String shortText(Object value){String s=toText(value);return s.length()>500?s.substring(0,500)+"…":s;}private String str(Object o){return o==null?null:String.valueOf(o);}
    public record ExecutionResult(Object output,String logs,Map<String,Object> nodeResults){}
}
