package com.linrun.interview.business.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 出题结构化输出测试")
class AgentQuestionOutputTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("兼容大模型返回的 snake_case 与 camelCase 追问字段")
  void supportsFollowUpAliases() throws Exception {
    AgentQuestionOutput snakeCase = objectMapper.readValue("""
        {"question":"Q1","rationale":"R1","is_follow_up":true,"evidence_ids":["chunk:1"]}
        """, AgentQuestionOutput.class);
    AgentQuestionOutput camelCase = objectMapper.readValue("""
        {"question":"Q2","rationale":"R2","isFollowUp":true,"evidenceIds":["chunk:2"],"extra":"ignored"}
        """, AgentQuestionOutput.class);

    assertThat(snakeCase.isFollowUp()).isTrue();
    assertThat(camelCase.isFollowUp()).isTrue();
    assertThat(snakeCase.evidenceIds()).containsExactly("chunk:1");
    assertThat(camelCase.evidenceIds()).containsExactly("chunk:2");
  }
}

