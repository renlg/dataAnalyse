package com.dataanalyse.workflow.engine;

import com.dataanalyse.apikey.service.ApiKeyService;
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

    private WorkflowNodeEntity node(String key,String type,String name){WorkflowNodeEntity n=new WorkflowNodeEntity();n.setNodeKey(key);n.setNodeType(type);n.setName(name);return n;}
}
