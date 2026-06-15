package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.JwtUtil;
import interview.guide.modules.user.model.AuthResponse;
import interview.guide.modules.user.model.LoginRequest;
import interview.guide.modules.user.model.RegisterRequest;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务：注册、登录、刷新 token。
 * 密码用 BCrypt 加密，token 用 JWT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        if (userRepository.existsByEmail(request.email())) {
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

        user = userRepository.save(user);
        log.info("User registered: userId={}, username={}", user.getId(), user.getUsername());

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误"));

        if (!user.getEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号已被禁用");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("User logged in: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        Long userId = jwtUtil.extractRefreshUserId(refreshToken);
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的 refresh token");
        }

        UserEntity user = userRepository.findById(userId)
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
