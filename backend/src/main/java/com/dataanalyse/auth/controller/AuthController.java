package com.dataanalyse.auth.controller;

import com.dataanalyse.auth.service.AuthService;
import com.dataanalyse.common.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    public ApiResult<Map<String, String>> login(@RequestBody Map<String, String> body) {
        AuthService.LoginResult r = authService.login(body.get("username"), body.get("password"));
        return ApiResult.ok(Map.of("token", r.token(), "username", r.username()));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpServletRequest request) {
        String token = (String) request.getAttribute("authToken");
        authService.logout(token);
        return ApiResult.ok(null);
    }

    @GetMapping("/me")
    public ApiResult<Map<String, String>> me(HttpServletRequest request) {
        String username = (String) request.getAttribute("authUsername");
        return ApiResult.ok(Map.of("username", username));
    }
}
