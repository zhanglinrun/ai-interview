package com.linrun.interview.modules.voiceinterview.config;

import com.linrun.interview.common.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 语音面试 WebSocket 握手 JWT 鉴权。
 *
 * <p>浏览器原生 WebSocket 无法自定义请求头，token 通过查询参数 {@code ?token=} 传递
 * （兼容 {@code Authorization: Bearer} 头，供非浏览器客户端使用）。
 * 校验通过后把 userId 写入 WebSocket session attributes，
 * 由 {@code VoiceInterviewWebSocketHandler} 在连接建立时做会话归属校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "userId";

    private final JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = resolveToken(request);
        Long userId = token != null ? jwtUtil.extractAccessUserId(token) : null;
        if (userId == null) {
            log.warn("语音面试 WebSocket 握手拒绝（无有效 token）: uri={}", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private String resolveToken(ServerHttpRequest request) {
        List<String> tokenParams = UriComponentsBuilder.fromUri(request.getURI())
            .build().getQueryParams().get("token");
        if (tokenParams != null && !tokenParams.isEmpty() && !tokenParams.getFirst().isBlank()) {
            return tokenParams.getFirst();
        }
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
