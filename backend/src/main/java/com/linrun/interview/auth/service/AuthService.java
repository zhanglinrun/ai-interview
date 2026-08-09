package com.linrun.interview.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.mapper.UserMapper;
import com.linrun.interview.auth.dto.AuthResponse;
import com.linrun.interview.auth.dto.LoginRequest;
import com.linrun.interview.auth.dto.RegisterRequest;
import com.linrun.interview.auth.entity.UserEntity;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 认证服务：注册、登录、注销和当前会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService extends ServiceImpl<UserMapper, UserEntity> {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (count(Wrappers.<UserEntity>lambdaQuery()
            .eq(UserEntity::getUsername, request.username())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        if (count(Wrappers.<UserEntity>lambdaQuery()
            .eq(UserEntity::getEmail, request.email())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱已被注册");
        }

        UserEntity user = UserEntity.builder()
            .username(request.username())
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .displayName(request.displayName() != null ? request.displayName() : request.username())
            .role(UserEntity.UserRole.USER)
            .enabled(true)
            .createdAt(LocalDateTime.now())
            .build();

        save(user);
        log.info("User registered: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserEntity user = Optional.ofNullable(getOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.username())))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误"));

        if (!user.getEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号已被禁用");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }

        user.setLastLoginAt(LocalDateTime.now());
        updateById(user);
        log.info("User logged in: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String token) {
        Object loginId = token == null || token.isBlank()
            ? null : StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无效的会话 token");
        }
        Long userId;
        try {
            userId = Long.valueOf(String.valueOf(loginId));
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无效的会话 token");
        }
        UserEntity user = Optional.ofNullable(getById(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户不存在"));
        if (!user.getEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号已被禁用");
        }
        return buildAuthResponse(user);
    }

    public void logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
    }

    public UserEntity currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Optional.ofNullable(getById(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或会话已失效"));
    }

    private AuthResponse buildAuthResponse(UserEntity user) {
        String role = user.getRole() != null ? user.getRole().name() : UserEntity.UserRole.USER.name();
        StpUtil.login(user.getId());
        StpUtil.getSession().set("role", role);
        String token = StpUtil.getTokenValue();
        return new AuthResponse(token, null, user.getId(), user.getUsername(), user.getDisplayName(), role);
    }
}
