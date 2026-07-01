package com.linrun.interview.modules.dingtalk.service;

import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTOHeaders;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTORequest;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTOResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;
import com.linrun.interview.modules.dingtalk.config.DingTalkProperties;
import com.linrun.interview.modules.dingtalk.util.DingTalkMessageBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 钉钉机器人单聊消息（对齐 know-engine RobotPrivateMessageService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dingtalk", name = "enabled", havingValue = "true")
public class RobotPrivateMessageService {

  private final DingTalkAccessTokenService accessTokenService;
  private final DingTalkProperties properties;
  private Client robotClient;

  @PostConstruct
  public void init() throws Exception {
    com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
    config.protocol = "https";
    config.regionId = "central";
    robotClient = new Client(config);
  }

  public String sendText(String text, String userId) throws Exception {
    return send(userId, "sampleText", DingTalkMessageBuilder.textJson(text));
  }

  public String sendMarkdown(String title, String text, String userId) throws Exception {
    return send(userId, "sampleMarkdown", DingTalkMessageBuilder.markdownJson(title, text));
  }

  public String send(String userId, String msgKey, String msgParamJson) throws Exception {
    BatchSendOTOHeaders headers = new BatchSendOTOHeaders();
    headers.setXAcsDingtalkAccessToken(accessTokenService.getAccessToken());

    BatchSendOTORequest request = new BatchSendOTORequest();
    request.setMsgKey(msgKey);
    request.setRobotCode(properties.getRobotCode());
    request.setUserIds(List.of(userId));
    request.setMsgParam(msgParamJson);

    try {
      BatchSendOTOResponse response = robotClient.batchSendOTOWithOptions(request, headers, new RuntimeOptions());
      if (Objects.isNull(response) || Objects.isNull(response.getBody())) {
        log.error("[RobotPrivateMessageService] 单聊消息发送失败: response={}", response);
        return null;
      }
      return response.getBody().getProcessQueryKey();
    } catch (TeaException e) {
      log.error("[RobotPrivateMessageService] 单聊 TeaException: code={}, msg={}",
          e.getCode(), e.getMessage(), e);
      throw e;
    }
  }
}
