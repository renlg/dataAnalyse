package com.dataanalyse.workflow.controller;
import com.dataanalyse.common.ApiResult;
import com.dataanalyse.workflow.service.WorkflowService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/runs")
public class WorkflowRunController {private final WorkflowService service;public WorkflowRunController(WorkflowService s){service=s;}@GetMapping("/{id}") public ApiResult<?> detail(@PathVariable Long id){return ApiResult.ok(service.getRun(id));}}
