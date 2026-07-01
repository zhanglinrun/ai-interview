package com.linrun.interview.modules.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.common.security.JwtUtil;
import com.linrun.interview.modules.user.mapper.UserMapper;
import com.linrun.interview.modules.user.model.AuthResponse;
import com.linrun.interview.modules.user.model.LoginRequest;
import com.linrun.interview.modules.user.model.RegisterRequest;
import com.linrun.interview.modules.user.model.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 认证服务：注册、登录、刷新 token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
            .eq(UserEntity::getUsername, request.username())) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        if (userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
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

        user = MapperUtils.save(userMapper, user);
        log.info("User registered: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserEntity user = Optional.ofNullable(userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.username())))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误"));

        if (!user.getEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号已被禁用");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }

        user.setLastLoginAt(LocalDateTime.now());
        MapperUtils.save(userMapper, user);
        log.info("User logged in: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        Long userId = jwtUtil.extractRefreshUserId(refreshToken);
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的 refresh token");
        }
        UserEntity user = Optional.ofNullable(userMapper.selectById(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户不存在"));
        if (!user.getEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号已被禁用");
        }
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(UserEntity user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        return new AuthResponse(accessToken, refreshToken, user.getId(), user.getUsername(), user.getDisplayName());
    }
}
