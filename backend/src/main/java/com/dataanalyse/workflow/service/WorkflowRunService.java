package com.dataanalyse.workflow.service;

import com.dataanalyse.common.BusinessException;
import com.dataanalyse.workflow.engine.WorkflowEngine;
import com.dataanalyse.workflow.entity.WorkflowRunEntity;
import com.dataanalyse.workflow.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class WorkflowRunService {
    private final WorkflowService workflows;private final WorkflowRunRepository runs;private final WorkflowEngine engine;private final Executor executor;private final ObjectMapper mapper;
    public WorkflowRunService(WorkflowService w,WorkflowRunRepository r,WorkflowEngine e,@Qualifier("workflowExecutor") Executor x,ObjectMapper m){workflows=w;runs=r;engine=e;executor=x;mapper=m;}
    @Transactional public Long trigger(Long workflowId,String triggerType){workflows.getEntity(workflowId);WorkflowRunEntity run=new WorkflowRunEntity();run.setWorkflowId(workflowId);run.setStatus("running");run.setStartedAt(LocalDateTime.now());run.setLogs("已触发（"+triggerType+"）");run=runs.save(run);Long id=run.getId();executor.execute(()->execute(id,workflowId,triggerType));return id;}
    public void execute(Long runId,Long workflowId,String triggerType){WorkflowRunEntity run=runs.findById(runId).orElseThrow(()->new BusinessException(404,"运行记录不存在"));try{WorkflowEngine.ExecutionResult result=engine.execute(workflows.getNodeEntities(workflowId),Map.of("trigger",triggerType,"time",LocalDateTime.now().toString()));run.setStatus("success");run.setLogs("运行成功\n"+result.logs()+"\n最终输出："+mapper.writeValueAsString(result.output()));}catch(Exception e){run.setStatus("failed");run.setLogs("运行失败："+e.getMessage());}run.setFinishedAt(LocalDateTime.now());runs.save(run);}
}
