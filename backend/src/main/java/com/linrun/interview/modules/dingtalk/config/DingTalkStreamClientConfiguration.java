package com.linrun.interview.modules.dingtalk.config;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.linrun.interview.modules.dingtalk.callback.ChatBotCallbackListener;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 钉钉 Stream 客户端（对齐 know-engine DingTalkStreamClientConfiguration）。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dingtalk", name = "stream-enabled", havingValue = "true")
public class DingTalkStreamClientConfiguration {

  private final DingTalkProperties properties;
  private final ChatBotCallbackListener chatBotCallbackListener;

  @Bean(initMethod = "start")
  public OpenDingTalkClient dingTalkStreamClient() throws Exception {
    return OpenDingTalkStreamClientBuilder.custom()
        .credential(new AuthClientCredential(properties.getAppKey(), properties.getAppSecret()))
        .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, chatBotCallbackListener)
        .build();
  }
}
