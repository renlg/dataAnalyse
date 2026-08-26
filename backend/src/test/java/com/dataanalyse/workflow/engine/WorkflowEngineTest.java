package com.dataanalyse.workflow.engine;

import com.dataanalyse.datasource.service.DataSourceService;
import com.dataanalyse.llm.LlmClient;
import com.dataanalyse.workflow.entity.WorkflowNodeEntity;
import com.dataanalyse.workflow.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowEngineTest {
    @Test void executesStartToEndTopologically(){WorkflowService workflows=mock(WorkflowService.class);WorkflowNodeEntity start=node("a","start","开始"),end=node("b","end","结束");when(workflows.parseConfig(start)).thenReturn(Map.of("_outgoing",List.of("b")));when(workflows.parseConfig(end)).thenReturn(Map.of());WorkflowEngine engine=new WorkflowEngine(workflows,mock(DataSourceService.class),mock(LlmClient.class),new ObjectMapper());WorkflowEngine.ExecutionResult result=engine.execute(List.of(start,end),"触发值");assertEquals("触发值",result.output());assertTrue(result.logs().contains("开始"));}
    private WorkflowNodeEntity node(String key,String type,String name){WorkflowNodeEntity n=new WorkflowNodeEntity();n.setNodeKey(key);n.setNodeType(type);n.setName(name);return n;}
}
