package com.dataanalyse.apikey.controller;

import com.dataanalyse.apikey.service.ApiKeyService;
import com.dataanalyse.common.ApiResult;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/keys")
public class ApiKeyController {
    private final ApiKeyService service; public ApiKeyController(ApiKeyService s){service=s;}
    @GetMapping public ApiResult<?> list(){return ApiResult.ok(service.list());}
    @GetMapping("/options") public ApiResult<?> options(){return ApiResult.ok(service.options());}
    @GetMapping("/{id}") public ApiResult<?> get(@PathVariable Long id){return ApiResult.ok(service.get(id));}
    @PostMapping public ApiResult<?> create(@RequestBody Map<String,Object> body){return ApiResult.ok(service.save(null,body));}
    @PutMapping("/{id}") public ApiResult<?> update(@PathVariable Long id,@RequestBody Map<String,Object> body){return ApiResult.ok(service.save(id,body));}
    @DeleteMapping("/{id}") public ApiResult<?> delete(@PathVariable Long id){service.delete(id);return ApiResult.ok(true);}
}
