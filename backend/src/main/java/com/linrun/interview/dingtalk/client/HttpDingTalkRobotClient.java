package com.linrun.interview.dingtalk.client;

import com.linrun.interview.dingtalk.service.DingTalkSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 钉钉机器人 HTTP 客户端，失败抛出异常由回调补偿任务接管。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpDingTalkRobotClient implements DingTalkRobotClient {

    private final DingTalkSignatureVerifier signatureVerifier;
    private final RestClient restClient = RestClient.create();

    @Override
    public void sendText(String webhook, String secret, String content, String atUserId) {
        if (!StringUtils.hasText(webhook)) {
            throw new IllegalArgumentException("钉钉机器人 webhook 未配置");
        }
        long timestamp = Instant.now().toEpochMilli();
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(webhook);
        if (StringUtils.hasText(secret)) {
            String timestampText = String.valueOf(timestamp);
            uri.queryParam("timestamp", timestampText)
                .queryParam("sign", signatureVerifier.signRobot(timestampText, secret));
        }
        Map<String, Object> body = Map.of(
            "msgtype", "text",
            "text", Map.of("content", content == null ? "" : content),
            "at", Map.of("atMobiles", List.of(),
                "atUserIds", StringUtils.hasText(atUserId) ? List.of(atUserId) : List.of(),
                "isAtAll", false));
        restClient.post()
            .uri(uri.build(true).toUri())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
        log.debug("钉钉机器人消息已发送: webhook={}", mask(webhook));
    }

    private String mask(String webhook) {
        int query = webhook.indexOf('?');
        return query > 0 ? webhook.substring(0, query) : webhook;
    }
}
