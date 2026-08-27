package com.dataanalyse.workflow.controller;
import com.dataanalyse.common.ApiResult;
import com.dataanalyse.workflow.service.WorkflowRunService;
import com.dataanalyse.workflow.service.WorkflowService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/runs")
public class WorkflowRunController {private final WorkflowService service;private final WorkflowRunService runService;public WorkflowRunController(WorkflowService s,WorkflowRunService r){service=s;runService=r;}
    @GetMapping public ApiResult<?> list(@RequestParam(required=false) Long workflowId){return ApiResult.ok(service.listRuns(workflowId));}
    @PostMapping("/cleanup-zombies") public ApiResult<?> cleanupZombies(@RequestParam(defaultValue="30") int olderThanMinutes){return ApiResult.ok(Map.of("cleaned",runService.cleanupZombies(olderThanMinutes)));}
    @GetMapping("/{id}") public ApiResult<?> detail(@PathVariable Long id){return ApiResult.ok(service.getRun(id));}}
