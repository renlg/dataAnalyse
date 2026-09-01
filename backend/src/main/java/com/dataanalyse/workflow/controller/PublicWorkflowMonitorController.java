package com.dataanalyse.workflow.controller;

import com.dataanalyse.common.ApiResult;
import com.dataanalyse.common.BusinessException;
import com.dataanalyse.workflow.entity.WorkflowEntity;
import com.dataanalyse.workflow.schedule.WorkflowScheduler;
import com.dataanalyse.workflow.service.WorkflowService;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** 公开监控接口仅返回流程展示所需信息，不返回节点配置、日志原文等敏感数据。 */
@RestController @RequestMapping("/api/public/monitor")
public class PublicWorkflowMonitorController {
    private final WorkflowService service;private final WorkflowScheduler scheduler;
    public PublicWorkflowMonitorController(WorkflowService s,WorkflowScheduler w){service=s;scheduler=w;}
    @GetMapping("/{workflowId}/info") public ApiResult<?> info(@PathVariable Long workflowId){WorkflowEntity workflow=enabledWorkflow(workflowId);Map<String,Object> schedule=scheduler.info(workflowId);Map<String,Object> result=new LinkedHashMap<>();result.put("workflowName",workflow.getName());result.put("monitorEnabled",true);result.put("cron",schedule.get("cron"));result.put("nextFireTime",schedule.get("nextFireTime"));result.put("nodes",service.getPublicNodes(workflowId));return ApiResult.ok(result);}
    @GetMapping("/{workflowId}/runs")
    public ApiResult<?> runs(@PathVariable Long workflowId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){enabledWorkflow(workflowId);return ApiResult.ok(service.getMonitorRuns(workflowId,page,size));}
    private WorkflowEntity enabledWorkflow(Long workflowId){WorkflowEntity workflow=service.getEntity(workflowId);if(!service.isMonitorEnabled(workflowId))throw new BusinessException(404,"该工作流未开启实时执行过程");return workflow;}
}
