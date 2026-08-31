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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.*;

@Component
public class WorkflowEngine {
    private final WorkflowService workflowService; private final DataSourceService dataSources; private final LlmClient llm; private final ObjectMapper mapper; private final ApiKeyService apiKeyService; private final PasswordCipher cipher;
    public WorkflowEngine(WorkflowService w,DataSourceService d,LlmClient l,ObjectMapper m,ApiKeyService a,PasswordCipher c){workflowService=w;dataSources=d;llm=l;mapper=m;apiKeyService=a;cipher=c;}
    public ExecutionResult execute(List<WorkflowNodeEntity> nodes,Object trigger){return execute(nodes,trigger,Map.of());}
    public ExecutionResult execute(List<WorkflowNodeEntity> nodes,Object trigger,Map<String,Object> contextParams){
        if(nodes.isEmpty())throw new BusinessException(400,"工作流没有节点");Map<String,WorkflowNodeEntity> byKey=new LinkedHashMap<>();Map<String,Integer> indegree=new HashMap<>();Map<String,List<String>> outgoing=new HashMap<>();Map<EdgeKey,String> edgeConditions=new HashMap<>();
        for(WorkflowNodeEntity n:nodes){byKey.put(n.getNodeKey(),n);indegree.put(n.getNodeKey(),0);}
        for(WorkflowNodeEntity n:nodes){for(OutgoingEdge edge:outgoingEdges(workflowService.parseConfig(n))){String target=edge.target();if(!byKey.containsKey(target))throw new BusinessException(400,"连线目标节点不存在："+target);outgoing.computeIfAbsent(n.getNodeKey(),k->new ArrayList<>()).add(target);if(edge.condition()!=null)edgeConditions.put(new EdgeKey(n.getNodeKey(),target),edge.condition());indegree.put(target,indegree.get(target)+1);}}
        Deque<String> ready=new ArrayDeque<>();indegree.forEach((k,v)->{if(v==0)ready.add(k);});Map<String,Object> results=new LinkedHashMap<>();
        // 节点名称→输出 的共享上下文，下游节点可用 ${节点名称} 引用任意前序节点输出
        Map<String,Object> nodeContext=new LinkedHashMap<>();
        List<String> logs=new ArrayList<>();int visited=0;Set<String> skipped=new HashSet<>();
        while(!ready.isEmpty()){String key=ready.remove();if(skipped.contains(key)){visited++;continue;}WorkflowNodeEntity node=byKey.get(key);List<Object> inputs=predecessorInputs(key,outgoing,results);Object input=inputs.size()==1?inputs.get(0):inputs;Object output;try{output=executeNode(node,input,trigger,contextParams,nodeContext);}catch(RuntimeException ex){throw new WorkflowExecutionException(node.getName(),ex,logs,results);}results.put(key,output);nodeContext.put(node.getName(),output);logs.add("节点 ["+node.getName()+"] 执行成功："+shortText(output));visited++;List<String> targets=outgoing.getOrDefault(key,List.of());if("condition".equals(node.getNodeType())){List<String> passed=new ArrayList<>(),failed=new ArrayList<>();for(String t:targets){String condition=edgeConditions.get(new EdgeKey(key,t));if(condition==null||condition.isBlank()||ConditionEvaluator.eval(render(condition,toText(output),contextParams,nodeContext),"",Map.of(),Map.of()))passed.add(t);else failed.add(t);}Set<String> protectedNodes=descendants(passed,outgoing);for(String t:failed)skipBranch(t,protectedNodes,outgoing,skipped,indegree,ready);for(String t:passed){int left=indegree.compute(t,(k,v)->v-1);if(left==0)ready.add(t);}continue;}for(String next:targets){int left=indegree.compute(next,(k,v)->v-1);if(left==0)ready.add(next);}}
        if(visited!=nodes.size())throw new BusinessException(400,"工作流存在循环，无法执行");Object overall=null;for(WorkflowNodeEntity n:nodes)if("end".equals(n.getNodeType()))overall=results.get(n.getNodeKey());if(overall==null&&!results.isEmpty())overall=new ArrayList<>(results.values()).get(results.size()-1);return new ExecutionResult(overall,String.join("\n",logs),results);
    }
    private Object executeNode(WorkflowNodeEntity node,Object input,Object trigger,Map<String,Object> contextParams,Map<String,Object> nodeContext){Map<String,Object> c=workflowService.parseConfig(node);String inputText=toText(input);return switch(node.getNodeType()){
        case "start" -> trigger;
        case "condition" -> input;
        case "end" -> {String output=str(c.get("output"));yield output==null||output.isBlank()?input:render(output,inputText,contextParams,nodeContext);}
        case "taiwei" -> {Map<String,Object> rc=resolveLlmConfig(c);yield callLlmWithRetry(()->llm.chat(str(rc.get("baseUrl")),str(rc.get("apiKey")),str(rc.get("model")),List.of(Map.of("role","system","content",render(str(c.get("prompt")),inputText,contextParams,nodeContext)))),retryCount(c));}
        case "llm" -> {Map<String,Object> rc=resolveLlmConfig(c);yield callLlmWithRetry(()->llm.chat(str(rc.get("baseUrl")),str(rc.get("apiKey")),str(rc.get("model")),List.of(Map.of("role","system","content",render(str(c.get("systemPrompt")),inputText,contextParams,nodeContext)),Map.of("role","user","content",render(str(c.get("userPrompt")),inputText,contextParams,nodeContext)))),retryCount(c));}
        case "h2sql","sqlitesql" -> {Object ds=c.get("dataSourceId");if(ds==null)throw new BusinessException(400,"SQL 节点未选择数据源");yield dataSources.queryForWorkflow(Long.valueOf(String.valueOf(ds)),"h2sql".equals(node.getNodeType())?"h2":"sqlite",render(str(c.get("sql")),inputText,contextParams,nodeContext));}
        case "python" -> executePython(str(c.get("code")),input,contextParams,nodeContext);
        default -> throw new BusinessException(400,"未知节点类型");};}
    private String callLlmWithRetry(Supplier<String> call,int retryCount){
        for(int attempt=0;;attempt++){
            try{return call.get();}
            catch(RuntimeException e){
                if(e instanceof BusinessException business&&business.getCode()==400)throw e;
                if(attempt>=retryCount)throw e;
                try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new BusinessException(500,"模型调用重试被中断");}
            }
        }
    }
    private int retryCount(Map<String,Object> config){
        Object value=config.get("retryCount");if(value==null||String.valueOf(value).isBlank())return 3;
        try{int count=Integer.parseInt(String.valueOf(value));if(count<0)throw new NumberFormatException();return count;}catch(NumberFormatException e){throw new BusinessException(400,"重试次数必须是大于等于 0 的整数");}
    }
    private Object executePython(String code,Object input,Map<String,Object> params,Map<String,Object> nodeContext){
        if(code==null||code.isBlank())throw new BusinessException(400,"Python 代码不能为空");
        Path stdout=null,stderr=null;Process process=null;
        try{
            Map<String,Object> context=new LinkedHashMap<>();context.put("input",input);context.put("params",params==null?Map.of():params);context.put("context",nodeContext==null?Map.of():nodeContext);
            String contextJson=mapper.writeValueAsString(context);
            // 上下文通过 stdin 传（不再作为命令行参数——Linux 单参数上限 128KB，大 JSON 会 Argument list too long）
            // 前缀：argv 不足时从 stdin 读，保持既有节点代码 `json.loads(sys.argv[1])` 完全兼容
            String script="import json, sys\nif len(sys.argv) < 2:\n    sys.argv.append(sys.stdin.read())\nctx = json.loads(sys.argv[1])\ninput = ctx.get('input')\nparams = ctx.get('params', {})\nnodeContext = ctx.get('context', {})\n"+code;
            stdout=Files.createTempFile("dataanalyse-python-",".out");stderr=Files.createTempFile("dataanalyse-python-",".err");
            process=new ProcessBuilder("python3","-c",script).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            // 先同步写完 stdin 再 waitFor：管道缓冲 64KB，超大数据也先落盘（子进程 stdin 读完整份才跑），避免死锁
            try(java.io.OutputStream os=process.getOutputStream()){os.write(contextJson.getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(30,TimeUnit.SECONDS)){
                process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);
                throw new BusinessException(500,"Python 执行超时（30 秒）");
            }
            String output=Files.readString(stdout,StandardCharsets.UTF_8).stripTrailing();
            String error=Files.readString(stderr,StandardCharsets.UTF_8).stripTrailing();
            if(process.exitValue()!=0)throw new BusinessException(500,"Python 执行失败："+(error.isBlank()?"退出码 "+process.exitValue():error));
            if(output.isBlank())return "";
            try{return mapper.readValue(output,Object.class);}catch(Exception ignored){return output;}
        }catch(BusinessException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();if(process!=null)process.destroyForcibly();throw new BusinessException(500,"Python 执行被中断");}
        catch(Exception e){if(process!=null)process.destroyForcibly();throw new BusinessException(500,"Python 执行失败："+e.getMessage());}
        finally{try{if(stdout!=null)Files.deleteIfExists(stdout);}catch(Exception ignored){}try{if(stderr!=null)Files.deleteIfExists(stderr);}catch(Exception ignored){}}
    }
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
    private List<String> outgoing(Map<String,Object> c){return outgoingEdges(c).stream().map(OutgoingEdge::target).toList();}
    private List<OutgoingEdge> outgoingEdges(Map<String,Object> c){Object value=c.get("_outgoing");if(!(value instanceof List<?> list))return List.of();List<OutgoingEdge> edges=new ArrayList<>();for(Object item:list){if(item instanceof String target)edges.add(new OutgoingEdge(target,null));else if(item instanceof Map<?,?> edge&&edge.get("target")!=null)edges.add(new OutgoingEdge(String.valueOf(edge.get("target")),edge.get("condition")==null?null:String.valueOf(edge.get("condition"))));else if(item!=null)edges.add(new OutgoingEdge(String.valueOf(item),null));}return edges;}
    private Set<String> descendants(Collection<String> roots,Map<String,List<String>> outgoing){Set<String> found=new HashSet<>();Deque<String> queue=new ArrayDeque<>(roots);while(!queue.isEmpty()){String key=queue.remove();if(found.add(key))queue.addAll(outgoing.getOrDefault(key,List.of()));}return found;}
    private void skipBranch(String key,Set<String> protectedNodes,Map<String,List<String>> outgoing,Set<String> skipped,Map<String,Integer> indegree,Deque<String> ready){if(protectedNodes.contains(key)){int left=indegree.compute(key,(k,v)->v-1);if(left==0){ready.add(key);}return;}if(!skipped.add(key))return;indegree.put(key,0);ready.add(key);for(String next:outgoing.getOrDefault(key,List.of()))skipBranch(next,protectedNodes,outgoing,skipped,indegree,ready);}
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
    private record OutgoingEdge(String target,String condition){}private record EdgeKey(String source,String target){}
    public record ExecutionResult(Object output,String logs,Map<String,Object> nodeResults){}
    /** 工作流执行中途某节点失败时抛出：携带已执行节点的日志与结果，便于失败详情展示 */
    public static class WorkflowExecutionException extends RuntimeException {
        private final String failedNode; private final List<String> partialLogs; private final Map<String,Object> partialResults;
        public WorkflowExecutionException(String failedNode,RuntimeException cause,List<String> logs,Map<String,Object> results){super("节点 ["+failedNode+"] 执行失败："+(cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage()),cause);this.failedNode=failedNode;this.partialLogs=new ArrayList<>(logs);this.partialResults=new LinkedHashMap<>(results);}
        public String getFailedNode(){return failedNode;}
        public List<String> getPartialLogs(){return partialLogs;}
        public Map<String,Object> getPartialResults(){return partialResults;}
    }
}
