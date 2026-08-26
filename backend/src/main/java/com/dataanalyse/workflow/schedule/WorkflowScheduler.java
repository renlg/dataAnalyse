package com.dataanalyse.workflow.schedule;

import com.dataanalyse.workflow.entity.WorkflowEntity;
import com.dataanalyse.workflow.repo.WorkflowRepository;
import com.dataanalyse.workflow.service.WorkflowRunService;
import com.dataanalyse.workflow.service.WorkflowService;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

@Component
public class WorkflowScheduler {
    private final ThreadPoolTaskScheduler scheduler;private final WorkflowRepository workflows;private final WorkflowService service;private final WorkflowRunService runService;private final Map<Long,ScheduledFuture<?>> futures=new HashMap<>();private final Map<Long,String> crons=new HashMap<>();
    public WorkflowScheduler(ThreadPoolTaskScheduler s,WorkflowRepository w,WorkflowService v,WorkflowRunService r){scheduler=s;workflows=w;service=v;runService=r;}
    @PostConstruct public void init(){workflows.findAll().forEach(w->refresh(w.getId()));}
    public synchronized void refresh(Long workflowId){ScheduledFuture<?> old=futures.remove(workflowId);if(old!=null)old.cancel(false);crons.remove(workflowId);Optional<WorkflowEntity> workflow=workflows.findById(workflowId);if(workflow.isEmpty()||!"active".equals(workflow.get().getStatus()))return;try{Map<String,Object> cfg=service.getWorkflowConfig(workflowId);String cron=String.valueOf(cfg.getOrDefault("cron",""));if(!cron.isBlank()){CronTrigger trigger=new CronTrigger(cron);ScheduledFuture<?> future=scheduler.schedule(()->runService.trigger(workflowId,"cron"),trigger);if(future!=null){futures.put(workflowId,future);crons.put(workflowId,cron);}}}catch(Exception ignored){/* 非法 cron 不影响启动 */}}
    public Map<String,Object> info(Long workflowId){service.getEntity(workflowId);String cron=crons.get(workflowId);Map<String,Object> result=new LinkedHashMap<>();result.put("cron",cron);if(cron!=null){CronTrigger trigger=new CronTrigger(cron);Date next=trigger.nextExecutionTime(new EmptyTriggerContext());result.put("nextFireTime",next==null?null:next.toInstant());}else result.put("nextFireTime",null);return result;}
    private static class EmptyTriggerContext implements TriggerContext {public Instant lastScheduledExecution(){return null;}public Instant lastActualExecution(){return null;}public Instant lastCompletion(){return null;}}
}
