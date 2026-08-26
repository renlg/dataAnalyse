package com.dataanalyse.workflow.controller;

import com.dataanalyse.common.ApiResult;
import com.dataanalyse.workflow.schedule.WorkflowScheduler;
import com.dataanalyse.workflow.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowService service;private final WorkflowRunService runs;private final WorkflowScheduler scheduler;
    public WorkflowController(WorkflowService s,WorkflowRunService r,WorkflowScheduler c){service=s;runs=r;scheduler=c;}
    @GetMapping public ApiResult<?> list(){return ApiResult.ok(service.list());}
    @PostMapping public ApiResult<?> create(@RequestBody Map<String,Object> body){return ApiResult.ok(service.save(null,body));}
    @GetMapping("/{id}") public ApiResult<?> detail(@PathVariable Long id){return ApiResult.ok(service.detail(id));}
    @PutMapping("/{id}") public ApiResult<?> update(@PathVariable Long id,@RequestBody Map<String,Object> body){Map<String,Object> result=service.save(id,body);scheduler.refresh(id);return ApiResult.ok(result);}
    @DeleteMapping("/{id}") public ApiResult<?> delete(@PathVariable Long id){service.delete(id);scheduler.refresh(id);return ApiResult.ok(true);}
    @GetMapping("/{id}/nodes") public ApiResult<?> nodes(@PathVariable Long id){return ApiResult.ok(service.getNodes(id));}
    @PutMapping("/{id}/nodes") public ApiResult<?> saveNodes(@PathVariable Long id,@RequestBody List<Map<String,Object>> body){List<Map<String,Object>> result=service.replaceNodes(id,body);scheduler.refresh(id);return ApiResult.ok(result);}
    @PostMapping("/{id}/run") public ApiResult<?> run(@PathVariable Long id){return ApiResult.ok(Map.of("runId",runs.trigger(id,"manual")));}
    @GetMapping("/{id}/runs") public ApiResult<?> history(@PathVariable Long id){return ApiResult.ok(service.getRuns(id));}
    @GetMapping("/{id}/schedule") public ApiResult<?> schedule(@PathVariable Long id){return ApiResult.ok(scheduler.info(id));}
}
