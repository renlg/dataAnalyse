package com.dataanalyse.workflow.service;

import com.dataanalyse.workflow.engine.WorkflowEngine;
import com.dataanalyse.workflow.entity.WorkflowEntity;
import com.dataanalyse.workflow.entity.WorkflowRunEntity;
import com.dataanalyse.workflow.repo.WorkflowNodeRepository;
import com.dataanalyse.workflow.repo.WorkflowRepository;
import com.dataanalyse.workflow.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowRunServiceTest {
    @AfterEach void clearSynchronization(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();}
    @Test void submitsOnlyAfterTransactionCommit(){WorkflowService workflows=mock(WorkflowService.class);WorkflowRunRepository runs=mock(WorkflowRunRepository.class);WorkflowExecutorManager executors=mock(WorkflowExecutorManager.class);WorkflowRunService service=new WorkflowRunService(workflows,runs,mock(WorkflowEngine.class),executors,new ObjectMapper());when(workflows.getEntity(7L)).thenReturn(new WorkflowEntity());when(workflows.getWorkflowConcurrency(7L)).thenReturn(3);when(runs.saveAndFlush(any())).thenAnswer(invocation->{WorkflowRunEntity run=invocation.getArgument(0);run.setId(11L);return run;});TransactionSynchronizationManager.initSynchronization();assertEquals(11L,service.trigger(7L,"manual"));verifyNoInteractions(executors);List<TransactionSynchronization> synchronizations=TransactionSynchronizationManager.getSynchronizations();assertEquals(1,synchronizations.size());synchronizations.get(0).afterCommit();verify(executors).execute(eq(7L),eq(3),any(Runnable.class));}
    @Test void cleanupMarksOnlyOldInactiveRunningRecordsFailed(){WorkflowService workflows=mock(WorkflowService.class);WorkflowRunRepository runs=mock(WorkflowRunRepository.class);WorkflowRunEntity zombie=new WorkflowRunEntity();zombie.setId(74L);zombie.setWorkflowId(1L);zombie.setStatus("running");zombie.setStartedAt(LocalDateTime.now().minusHours(2));zombie.setLogs("已触发（manual）");when(runs.findByStatusAndStartedAtBefore(eq("running"),any())).thenReturn(List.of(zombie));WorkflowRunService service=new WorkflowRunService(workflows,runs,mock(WorkflowEngine.class),mock(WorkflowExecutorManager.class),new ObjectMapper());assertEquals(1,service.cleanupZombies(30));assertEquals("failed",zombie.getStatus());assertNotNull(zombie.getFinishedAt());assertTrue(zombie.getLogs().contains("系统清理"));verify(runs).saveAll(List.of(zombie));}
    @Test void workflowConfigDefaultsConcurrencyToTen(){WorkflowRepository workflowRepository=mock(WorkflowRepository.class);WorkflowEntity workflow=new WorkflowEntity();workflow.setId(9L);when(workflowRepository.findById(9L)).thenReturn(java.util.Optional.of(workflow));WorkflowService workflows=new WorkflowService(workflowRepository,mock(WorkflowNodeRepository.class),mock(WorkflowRunRepository.class),new ObjectMapper());assertEquals(10,workflows.getWorkflowConcurrency(9L));}
    @Test void executeTruncatesFailureLogToEightyChars(){WorkflowService workflows=mock(WorkflowService.class);WorkflowRunRepository runs=mock(WorkflowRunRepository.class);WorkflowEngine engine=mock(WorkflowEngine.class);WorkflowRunEntity run=new WorkflowRunEntity();run.setId(50L);run.setWorkflowId(4L);run.setStatus("running");when(runs.findById(50L)).thenReturn(java.util.Optional.of(run));when(workflows.getWorkflowConfig(4L)).thenReturn(Map.of());when(workflows.getNodeEntities(4L)).thenReturn(List.of());String longMessage="运行失败："+"X".repeat(500);when(engine.execute(any(),any(),any())).thenThrow(new RuntimeException(longMessage));WorkflowRunService service=new WorkflowRunService(workflows,runs,engine,mock(WorkflowExecutorManager.class),new ObjectMapper());service.execute(50L,4L,"manual");assertEquals("failed",run.getStatus());assertTrue(run.getLogs().length()<=83,"失败日志应截断到 80 字左右: len="+run.getLogs().length());assertTrue(run.getLogs().endsWith("..."),"超长应加省略号");assertTrue(run.getLogs().startsWith("运行失败："));}
    @Test void executeKeepsShortFailureLogUnchanged(){WorkflowService workflows=mock(WorkflowService.class);WorkflowRunRepository runs=mock(WorkflowRunRepository.class);WorkflowEngine engine=mock(WorkflowEngine.class);WorkflowRunEntity run=new WorkflowRunEntity();run.setId(51L);run.setWorkflowId(4L);run.setStatus("running");when(runs.findById(51L)).thenReturn(java.util.Optional.of(run));when(workflows.getWorkflowConfig(4L)).thenReturn(Map.of());when(workflows.getNodeEntities(4L)).thenReturn(List.of());when(engine.execute(any(),any(),any())).thenThrow(new RuntimeException("Python 执行失败：语法错误"));WorkflowRunService service=new WorkflowRunService(workflows,runs,engine,mock(WorkflowExecutorManager.class),new ObjectMapper());service.execute(51L,4L,"manual");assertEquals("failed",run.getStatus());assertEquals("运行失败：Python 执行失败：语法错误",run.getLogs());}
}
