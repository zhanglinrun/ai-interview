package com.linrun.interview.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("查询改写规则测试")
class InterviewQueryRewriteRulesTest {

  @Test
  @DisplayName("应纠正常见技术术语拼写")
  void fixesCommonTypos() {
    assertThat(InterviewQueryRewriteRules.applyRules("sping boot 怎么学"))
        .contains("Spring Boot");
  }

  @Test
  @DisplayName("应展开缩写并口语化映射")
  void expandsAbbreviations() {
    assertThat(InterviewQueryRewriteRules.applyRules("jvm gc 原理"))
        .contains("JVM 垃圾回收");
    assertThat(InterviewQueryRewriteRules.applyRules("redis 挂了怎么办"))
        .contains("Redis 故障排查与高可用");
  }
}
