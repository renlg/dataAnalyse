package com.dataanalyse.workflow.engine;

import com.dataanalyse.apikey.service.ApiKeyService;
import com.dataanalyse.common.BusinessException;
import com.dataanalyse.datasource.service.DataSourceService;
import com.dataanalyse.datasource.service.PasswordCipher;
import com.dataanalyse.llm.LlmClient;
import com.dataanalyse.workflow.entity.WorkflowNodeEntity;
import com.dataanalyse.workflow.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowEngineTest {
    @Test void executesStartToEndTopologically(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity start=node("a","start","开始"),end=node("b","end","结束");when(workflows.parseConfig(start)).thenReturn(Map.of("_outgoing",List.of("b")));when(workflows.parseConfig(end)).thenReturn(Map.of());WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),mock(LlmClient.class),new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));WorkflowEngine.ExecutionResult result=engine.execute(List.of(start,end),"触发值");assertEquals("触发值",result.output());assertTrue(result.logs().contains("开始"));}
    @Test void injectsContextParamsIntoTemplate(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity start=node("a","start","开始"),end=node("b","end","结束");when(workflows.parseConfig(start)).thenReturn(Map.of("_outgoing",List.of("b")));when(workflows.parseConfig(end)).thenReturn(Map.of("output","参数=${stockCode} 上游={{input}}"));WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),mock(LlmClient.class),new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));WorkflowEngine.ExecutionResult result=engine.execute(List.of(start,end),"上游值",Map.of("stockCode","000001"));assertEquals("参数=000001 上游=上游值",result.output());}
    @Test void retriesTransientLlmFailures(){WorkflowService workflows=mock(WorkflowService.class);LlmClient llm=mock(LlmClient.class);WorkflowNodeEntity node=node("llm","llm","模型");when(workflows.parseConfig(node)).thenReturn(Map.of("baseUrl","http://localhost","apiKey","key","model","model","systemPrompt","system","userPrompt","user","retryCount",3));when(llm.chat(anyString(),anyString(),anyString(),anyList())).thenThrow(new BusinessException(502,"临时失败")).thenThrow(new BusinessException(502,"临时失败")).thenReturn("成功");WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),llm,new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));WorkflowEngine.ExecutionResult result=engine.execute(List.of(node),null);assertEquals("成功",result.output());verify(llm,times(3)).chat(anyString(),anyString(),anyString(),anyList());}
    @Test void executesPythonAndReturnsText(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity node=node("python","python","运行 Python");when(workflows.parseConfig(node)).thenReturn(Map.of("code","print(\"hello\")"));WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),mock(LlmClient.class),new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));assertEquals("hello",engine.execute(List.of(node),null).output());}
    @Test void executesPythonAndParsesJson(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity node=node("python","python","运行 Python");when(workflows.parseConfig(node)).thenReturn(Map.of("code","import json;print(json.dumps({\"a\":1}))"));WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),mock(LlmClient.class),new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));Object output=engine.execute(List.of(node),null).output();assertInstanceOf(Map.class,output);assertEquals(1,((Map<?,?>)output).get("a"));}
    @Test void conditionExecutesTrueBranchAndSkipsFalseBranchWithoutCycleError(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity start=node("a","start","开始"),condition=node("c","condition","判断"),success=node("ok","end","成功结束"),failed=node("bad","llm","不该执行"),failedEnd=node("bad-end","end","失败结束");when(workflows.parseConfig(start)).thenReturn(Map.of("_outgoing",List.of("c")));when(workflows.parseConfig(condition)).thenReturn(Map.of("_outgoing",List.of(Map.of("target","ok","condition","{{input}} == '成功'"),Map.of("target","bad","condition","{{input}} != '成功'"))));when(workflows.parseConfig(success)).thenReturn(Map.of("output","结果：{{input}}"));when(workflows.parseConfig(failed)).thenReturn(Map.of("_outgoing",List.of("bad-end"),"baseUrl","http://x","apiKey","k","model","m","systemPrompt","s","userPrompt","u","retryCount",0));when(workflows.parseConfig(failedEnd)).thenReturn(Map.of());LlmClient llmMock=mock(LlmClient.class);WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),llmMock,new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));WorkflowEngine.ExecutionResult result=engine.execute(List.of(start,condition,success,failed,failedEnd),"成功");assertEquals("结果：成功",result.output());assertFalse(result.logs().contains("工作流存在循环"));verify(llmMock,never()).chat(anyString(),anyString(),anyString(),anyList());}

    @Test void conditionPassesInputThrough(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity start=node("a","start","开始"),condition=node("c","condition","判断");when(workflows.parseConfig(start)).thenReturn(Map.of("_outgoing",List.of("c")));when(workflows.parseConfig(condition)).thenReturn(Map.of());WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),mock(LlmClient.class),new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));WorkflowEngine.ExecutionResult result=engine.execute(List.of(start,condition),"原始输入");assertEquals("原始输入",result.nodeResults().get("c"));assertEquals("原始输入",result.output());}
    @Test void conditionUnconditionalEdgeAlwaysExecutes(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity start=node("a","start","开始"),condition=node("c","condition","判断"),end=node("b","end","结束");when(workflows.parseConfig(start)).thenReturn(Map.of("_outgoing",List.of("c")));when(workflows.parseConfig(condition)).thenReturn(Map.of("_outgoing",List.of("b")));when(workflows.parseConfig(end)).thenReturn(Map.of("output","已执行"));WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),mock(LlmClient.class),new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));assertEquals("已执行",engine.execute(List.of(start,condition,end),"任意值").output());}
    @Test void nodeFailureThrowsWorkflowExecutionExceptionWithPartialResults(){
        // 开始执行成功 → llm 节点失败：异常应携带已执行节点(开始)的结果+日志+失败节点名
        WorkflowService workflows=mock(WorkflowService.class);
        LlmClient llm=mock(LlmClient.class);
        WorkflowNodeEntity start=node("a","start","开始"),bad=node("b","llm","AI批量打标");
        when(workflows.parseConfig(start)).thenReturn(Map.of("_outgoing",List.of("b")));
        when(workflows.parseConfig(bad)).thenReturn(Map.of("baseUrl","http://x","apiKey","k","model","m","systemPrompt","s","userPrompt","u","retryCount",0));
        when(llm.chat(anyString(),anyString(),anyString(),anyList())).thenThrow(new BusinessException(502,"模型挂了"));
        WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),llm,new ObjectMapper(),mock(ApiKeyService.class),mock(PasswordCipher.class));
        WorkflowEngine.WorkflowExecutionException ex=assertThrows(WorkflowEngine.WorkflowExecutionException.class,()->engine.execute(List.of(start,bad),"触发"));
        assertEquals("AI批量打标",ex.getFailedNode());
        assertTrue(ex.getMessage().contains("模型挂了"));
        // 部分结果含已执行节点 开始
        assertNotNull(ex.getPartialResults());
        assertEquals("触发",ex.getPartialResults().get("a"));
        // 部分日志含已执行节点
        assertTrue(ex.getPartialLogs().stream().anyMatch(l->l.contains("开始")));
        // 未执行节点不在部分结果里
        assertFalse(ex.getPartialResults().containsKey("b"));
    }

    private WorkflowNodeEntity node(String key,String type,String name){WorkflowNodeEntity n=new WorkflowNodeEntity();n.setNodeKey(key);n.setNodeType(type);n.setName(name);return n;}
}
