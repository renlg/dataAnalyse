package com.dataanalyse.common;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResult<Void>> business(BusinessException e) {
        return ResponseEntity.status(e.getCode() >= 500 ? 500 : e.getCode()).body(ApiResult.error(e.getCode(), e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResult<Void>> validation(Exception e) {
        String message = "请求参数不正确";
        if (e instanceof MethodArgumentNotValidException ex && ex.getBindingResult().getFieldError() != null) message = ex.getBindingResult().getFieldError().getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResult.error(400, message));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> unknown(Exception e) {
        return ResponseEntity.internalServerError().body(ApiResult.error(500, "服务器处理失败：" + e.getMessage()));
    }
}
