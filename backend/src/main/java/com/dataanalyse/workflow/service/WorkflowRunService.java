package com.dataanalyse.workflow.service;

import com.dataanalyse.common.BusinessException;
import com.dataanalyse.workflow.engine.WorkflowEngine;
import com.dataanalyse.workflow.entity.WorkflowRunEntity;
import com.dataanalyse.workflow.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowRunService {
    private final WorkflowService workflows;private final WorkflowRunRepository runs;private final WorkflowEngine engine;private final WorkflowExecutorManager executors;private final ObjectMapper mapper;private final Set<Long> activeRuns=ConcurrentHashMap.newKeySet();
    public WorkflowRunService(WorkflowService w,WorkflowRunRepository r,WorkflowEngine e,WorkflowExecutorManager x,ObjectMapper m){workflows=w;runs=r;engine=e;executors=x;mapper=m;}
    @Transactional public Long trigger(Long workflowId,String triggerType){workflows.getEntity(workflowId);int concurrency=workflows.getWorkflowConcurrency(workflowId);WorkflowRunEntity run=new WorkflowRunEntity();run.setWorkflowId(workflowId);run.setStatus("running");run.setStartedAt(LocalDateTime.now());run.setLogs("已触发（"+triggerType+"）");run=runs.saveAndFlush(run);Long id=run.getId();Runnable submit=()->submit(id,workflowId,triggerType,concurrency);if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){submit.run();}});else submit.run();return id;}
    private void submit(Long runId,Long workflowId,String triggerType,int concurrency){activeRuns.add(runId);try{executors.execute(workflowId,concurrency,()->{try{execute(runId,workflowId,triggerType);}finally{activeRuns.remove(runId);}});}catch(Exception e){activeRuns.remove(runId);markFailed(runId,"任务提交失败："+e.getMessage());}}
    public void execute(Long runId,Long workflowId,String triggerType){WorkflowRunEntity run=null;try{run=runs.findById(runId).orElseThrow(()->new BusinessException(404,"运行记录不存在"));Map<String,Object> wfConfig=workflows.getWorkflowConfig(workflowId);@SuppressWarnings("unchecked") Map<String,Object> params=wfConfig.get("params") instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();WorkflowEngine.ExecutionResult result=engine.execute(workflows.getNodeEntities(workflowId),Map.of("trigger",triggerType,"time",LocalDateTime.now().toString()),params);run.setStatus("success");run.setLogs("运行成功\n"+result.logs()+"\n最终输出："+mapper.writeValueAsString(result.output()));run.setNodeResults(mapper.writeValueAsString(result.nodeResults()));}catch(WorkflowEngine.WorkflowExecutionException e){if(run==null){markFailed(runId,e.getMessage());return;}run.setStatus("failed");String logs=(e.getPartialLogs().isEmpty()?"":"已执行节点日志：\n"+String.join("\n",e.getPartialLogs())+"\n")+"运行失败：节点 ["+e.getFailedNode()+"] 执行失败："+(e.getCause().getMessage()==null?e.getCause().getClass().getSimpleName():e.getCause().getMessage());run.setLogs(logs);try{run.setNodeResults(mapper.writeValueAsString(e.getPartialResults()));}catch(Exception ignore){run.setNodeResults(null);}}catch(Exception e){String fail=truncate("运行失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),80);if(run==null){markFailed(runId,fail);return;}run.setStatus("failed");run.setLogs(fail);run.setNodeResults(null);}run.setFinishedAt(LocalDateTime.now());runs.save(run);}
    /** 截断失败日志到 max 字符以内（中文按字符计），超长加省略号 */
    private String truncate(String s,int max){if(s==null)return "";if(s.length()<=max)return s;return s.substring(0,max)+"...";}
    private void markFailed(Long runId,String message){runs.findById(runId).ifPresent(run->{run.setStatus("failed");run.setFinishedAt(LocalDateTime.now());run.setLogs(message);run.setNodeResults(null);runs.save(run);});}
    @Transactional public int cleanupZombies(int olderThanMinutes){if(olderThanMinutes<1)throw new BusinessException(400,"清理阈值必须大于 0 分钟");LocalDateTime now=LocalDateTime.now();List<WorkflowRunEntity> zombies=runs.findByStatusAndStartedAtBefore("running",now.minusMinutes(olderThanMinutes)).stream().filter(run->!activeRuns.contains(run.getId())).toList();zombies.forEach(run->{run.setStatus("failed");run.setFinishedAt(now);String logs=run.getLogs();run.setLogs((logs==null||logs.isBlank()?"":logs+"\n")+"系统清理：运行超过 "+olderThanMinutes+" 分钟且无活动任务，已标记失败");run.setNodeResults(null);});runs.saveAll(zombies);return zombies.size();}
}
