package com.linrun.interview.modules.dingtalk.service;

import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendHeaders;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendRequest;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendResponse;
import com.aliyun.tea.TeaException;
import com.linrun.interview.modules.dingtalk.config.DingTalkProperties;
import com.linrun.interview.modules.dingtalk.util.DingTalkMessageBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 钉钉机器人群聊消息（对齐 know-engine RobotGroupMessagesService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dingtalk", name = "enabled", havingValue = "true")
public class RobotGroupMessagesService {

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

  public String sendText(String openConversationId, String text) throws Exception {
    return send(openConversationId, "sampleText", DingTalkMessageBuilder.textJson(text));
  }

  public String sendMarkdown(String openConversationId, String title, String text) throws Exception {
    return send(openConversationId, "sampleMarkdown", DingTalkMessageBuilder.markdownJson(title, text));
  }

  public String send(String openConversationId, String msgKey, String msgParamJson) throws Exception {
    OrgGroupSendHeaders headers = new OrgGroupSendHeaders();
    headers.setXAcsDingtalkAccessToken(accessTokenService.getAccessToken());

    OrgGroupSendRequest request = new OrgGroupSendRequest();
    request.setMsgKey(msgKey);
    request.setRobotCode(properties.getRobotCode());
    request.setOpenConversationId(openConversationId);
    request.setMsgParam(msgParamJson);

    try {
      OrgGroupSendResponse response = robotClient.orgGroupSendWithOptions(
          request, headers, new com.aliyun.teautil.models.RuntimeOptions());
      if (Objects.isNull(response) || Objects.isNull(response.getBody())) {
        log.error("[RobotGroupMessagesService] 群消息发送失败: response={}", response);
        return null;
      }
      return response.getBody().getProcessQueryKey();
    } catch (TeaException e) {
      log.error("[RobotGroupMessagesService] 群消息 TeaException: code={}, msg={}",
          e.getCode(), e.getMessage(), e);
      throw e;
    }
  }
}
