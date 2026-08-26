package com.dataanalyse.datasource.controller;

import com.dataanalyse.common.ApiResult;
import com.dataanalyse.datasource.service.DataSourceService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/datasources")
public class DataSourceController {
    private final DataSourceService service; public DataSourceController(DataSourceService s){service=s;}
    @GetMapping public ApiResult<?> list(){return ApiResult.ok(service.list());}
    @GetMapping("/{id}") public ApiResult<?> get(@PathVariable Long id){return ApiResult.ok(service.get(id));}
    @PostMapping public ApiResult<?> create(@RequestBody Map<String,Object> body){return ApiResult.ok(service.save(null,body));}
    @PutMapping("/{id}") public ApiResult<?> update(@PathVariable Long id,@RequestBody Map<String,Object> body){return ApiResult.ok(service.save(id,body));}
    @DeleteMapping("/{id}") public ApiResult<?> delete(@PathVariable Long id){service.delete(id);return ApiResult.ok(true);}
    @PostMapping("/{id}/test") public ApiResult<?> test(@PathVariable Long id){boolean ok=service.test(id);if(!ok)throw new com.dataanalyse.common.BusinessException(400,"连接失败，请检查配置");return ApiResult.ok(Map.of("online",true));}
    @PostMapping("/{id}/query") public ApiResult<?> query(@PathVariable Long id,@RequestBody Map<String,Object> body){return ApiResult.ok(service.query(id,String.valueOf(body.getOrDefault("sql",""))));}
}
