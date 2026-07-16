package com.linrun.interview.modules.interviewschedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("面试邀约轮次解析")
class InterviewParseServiceTest {

  private InterviewParseService service;

  @BeforeEach
  void setUp() {
    service = new InterviewParseService(null, null, null);
  }

  @Test
  @DisplayName("前端公开示例中的一面与第一轮不会触发数字转换异常")
  void shouldParseRoundFromPublicExample() {
    String publicExample = """
        【阿里巴巴】后端开发工程师一面邀请
        候选人：张三
        面试时间：2026-04-15 19:30
        面试形式：视频面试（腾讯会议）
        会议链接：https://meeting.tencent.com/abc-defg-hij
        面试轮次：第一轮技术面
        面试官：李老师
        备注：请提前10分钟入会，准备项目介绍与系统设计案例。
        """;

    assertThat(service.parseRoundNumber(publicExample)).isEqualTo(1);
    assertThat(service.parseRoundNumber("二面")).isEqualTo(2);
    assertThat(service.parseRoundNumber("第十轮")).isEqualTo(10);
    assertThat(service.parseRoundNumber("第12轮")).isEqualTo(12);
  }

  @Test
  @DisplayName("空轮次和非数字轮次安全回退为第一轮")
  void shouldDefaultEmptyOrInvalidRoundToFirstRound() {
    assertThat(service.parseRoundNumber(null)).isEqualTo(1);
    assertThat(service.parseRoundNumber("")).isEqualTo(1);
    assertThat(service.parseRoundNumber("   ")).isEqualTo(1);
    assertThat(service.parseRoundNumber("技术面")).isEqualTo(1);
    assertThat(service.parseRoundNumber("9".repeat(100))).isEqualTo(1);
  }
}
