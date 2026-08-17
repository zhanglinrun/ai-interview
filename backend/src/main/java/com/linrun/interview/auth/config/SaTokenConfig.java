package com.linrun.interview.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.auth.mapper.UserMapper;
import com.linrun.interview.auth.entity.UserEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 统一认证入口。
 *
 * <p>认证只在这一层完成，领域服务通过 {@code UserContext} 读取当前用户，
 * 不再自行解析 Authorization token。Redis 由 Sa-Token starter 承担会话持久化。</p>
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private static final String[] PUBLIC_PATHS = {
        "/api/v1/auth/**",
        "/api/v1/health/**",
        "/actuator/**",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/error"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle ->
                SaRouter.match("/api/v1/**")
                    .notMatch(PUBLIC_PATHS)
                    .check(r -> StpUtil.checkLogin())))
            .addPathPatterns("/api/v1/**");
    }

    /**
     * 角色信息由数据库统一提供给 Sa-Token 的注解鉴权能力。
     */
    @Bean
    public StpInterface stpInterface(UserMapper userMapper) {
        return new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return Collections.emptyList();
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                Long userId;
                try {
                    userId = Long.valueOf(String.valueOf(loginId));
                } catch (NumberFormatException ignored) {
                    return Collections.emptyList();
                }
                UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                    .select(UserEntity::getRole)
                    .eq(UserEntity::getId, userId));
                if (user == null || user.getRole() == null) {
                    return Collections.emptyList();
                }
                return List.of(user.getRole().name().toLowerCase());
            }
        };
    }
}
