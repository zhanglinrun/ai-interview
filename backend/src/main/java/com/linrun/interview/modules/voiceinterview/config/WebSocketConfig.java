package com.linrun.interview.modules.voiceinterview.config;

import com.linrun.interview.common.config.CorsProperties;
import com.linrun.interview.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

// 仅在 Servlet Web 环境注册：评测/离线测试用 WebEnvironment.NONE 起上下文时
// ServletServerContainerFactoryBean 拿不到 ServletContext 会直接启动失败
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceInterviewWebSocketHandler voiceInterviewWebSocketHandler;
    private final CorsProperties corsProperties;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
                .addInterceptors(jwtHandshakeInterceptor, new HttpSessionHandshakeInterceptor())
                .setAllowedOrigins(corsProperties.getAllowedOrigins().split(","));
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(2 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024);
        return container;
    }
}
