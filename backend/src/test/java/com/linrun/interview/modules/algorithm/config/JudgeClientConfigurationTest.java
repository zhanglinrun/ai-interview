package com.linrun.interview.modules.algorithm.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.algorithm.client.Judge0JudgeClient;
import com.linrun.interview.modules.algorithm.client.JudgeClient;
import com.linrun.interview.modules.algorithm.client.JudgeClientResult;
import com.linrun.interview.modules.algorithm.client.JudgeRequest;
import com.linrun.interview.modules.algorithm.client.UnavailableJudgeClient;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import com.linrun.interview.modules.algorithm.model.JudgeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("JudgeClient 条件装配")
class JudgeClientConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(JudgeClientConfiguration.class)
      .withBean(ObjectMapper.class, ObjectMapper::new);

  @Test
  @DisplayName("Judge0 关闭时只装配不访问网络的降级客户端")
  void shouldCreateUnavailableClientWhenDisabled() {
    contextRunner
        .withPropertyValues("app.algorithm.judge0.enabled=false")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(JudgeClient.class);
          assertThat(context).hasSingleBean(UnavailableJudgeClient.class);
          assertThat(context).doesNotHaveBean(Judge0JudgeClient.class);
          JudgeClient client = context.getBean(JudgeClient.class);
          assertThat(client.providerName()).isEqualTo("JUDGE0_DISABLED");
          assertThat(client.available(CodingLanguage.JAVA21)).isFalse();
          JudgeClientResult result = client.judge(request());
          assertThat(result.status()).isEqualTo(JudgeStatus.UNAVAILABLE);
          assertThat(result.failureCode()).isEqualTo("JUDGE_NOT_CONFIGURED");
        });
  }

  @Test
  @DisplayName("未配置 Judge0 开关时默认只装配降级客户端")
  void shouldCreateUnavailableClientWhenPropertyIsMissing() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(JudgeClient.class);
      assertThat(context).hasSingleBean(UnavailableJudgeClient.class);
      assertThat(context).doesNotHaveBean(Judge0JudgeClient.class);
    });
  }

  @Test
  @DisplayName("Judge0 开启时只装配真实客户端")
  void shouldCreateJudge0ClientWhenEnabled() {
    contextRunner
        .withPropertyValues(
            "app.algorithm.judge0.enabled=true",
            "app.algorithm.judge0.base-url=http://127.0.0.1:9",
            "app.algorithm.judge0.java21-language-id=62")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(JudgeClient.class);
          assertThat(context).hasSingleBean(Judge0JudgeClient.class);
          assertThat(context).doesNotHaveBean(UnavailableJudgeClient.class);
          JudgeClient client = context.getBean(JudgeClient.class);
          assertThat(client.providerName()).isEqualTo("JUDGE0");
          assertThat(client.available(CodingLanguage.JAVA21)).isTrue();
        });
  }

  private JudgeRequest request() {
    return new JudgeRequest(
        "submission-id", CodingLanguage.JAVA21, "class Main {}",
        "AIJUDGE_RESULT:1/1", 1);
  }
}
