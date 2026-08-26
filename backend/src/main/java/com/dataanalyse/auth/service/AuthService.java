package com.dataanalyse.auth.service;

import com.dataanalyse.auth.entity.AuthTokenEntity;
import com.dataanalyse.auth.entity.UserEntity;
import com.dataanalyse.auth.repo.AuthTokenRepository;
import com.dataanalyse.auth.repo.UserRepository;
import com.dataanalyse.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {
    private final UserRepository userRepo;
    private final AuthTokenRepository tokenRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    @Value("${dataanalyse.admin.username:admin}") private String defaultAdminUsername;
    @Value("${dataanalyse.admin.password:admin123}") private String defaultAdminPassword;

    public AuthService(UserRepository userRepo, AuthTokenRepository tokenRepo) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
    }

    @PostConstruct
    public void init() {
        tokenRepo.deleteExpired(LocalDateTime.now());
        if (userRepo.count() == 0) {
            UserEntity u = new UserEntity();
            u.setUsername(defaultAdminUsername);
            u.setPasswordHash(encoder.encode(defaultAdminPassword));
            userRepo.save(u);
        }
    }

    public record LoginResult(String token, String username) {}

    public LoginResult login(String username, String password) {
        if (username == null || password == null) throw new BusinessException(401, "用户名或密码错误");
        Optional<UserEntity> opt = userRepo.findByUsername(username);
        if (opt.isEmpty()) throw new BusinessException(401, "用户名或密码错误");
        UserEntity u = opt.get();
        if (!encoder.matches(password, u.getPasswordHash())) throw new BusinessException(401, "用户名或密码错误");
        String token = generateToken();
        AuthTokenEntity t = new AuthTokenEntity();
        t.setToken(token);
        t.setUsername(u.getUsername());
        t.setExpiresAt(LocalDateTime.now().plusDays(7));
        tokenRepo.save(t);
        return new LoginResult(token, u.getUsername());
    }

    public void logout(String token) {
        if (token != null) tokenRepo.deleteById(token);
    }

    public String resolveUsername(String token) {
        if (token == null) return null;
        Optional<AuthTokenEntity> opt = tokenRepo.findById(token);
        if (opt.isEmpty()) return null;
        AuthTokenEntity t = opt.get();
        if (t.getExpiresAt().isBefore(LocalDateTime.now())) {
            tokenRepo.deleteById(token);
            return null;
        }
        return t.getUsername();
    }

    @Transactional
    public void cleanExpired() {
        tokenRepo.deleteExpired(LocalDateTime.now());
    }

    private String generateToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
