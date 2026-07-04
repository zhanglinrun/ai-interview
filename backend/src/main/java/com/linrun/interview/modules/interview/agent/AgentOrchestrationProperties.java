package com.linrun.interview.modules.interview.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Multi-Agent 面试编排配置（Planner/Interviewer/Critic/Evaluator + Reflexion）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.agent")
public class AgentOrchestrationProperties {

  /** 是否启用 Multi-Agent 编排出题（关闭时主流程回退批量出题） */
  private boolean enabled = true;

  /** Critic 审题开关（关闭=Interviewer 直出，供 A/B 对比） */
  private boolean criticEnabled = true;

  /** Reflexion 反思重生成上限（Critic 不合格时 Interviewer 重出题的最大次数） */
  private int maxReflexion = 2;

  /** 会话内 ChatMemory 窗口大小（消息条数） */
  private int memoryWindow = 20;

  /** 是否上报编排指标 */
  private boolean metricsEnabled = true;

  /** 跨会话候选人画像记忆 */
  private CandidateMemory candidateMemory = new CandidateMemory();

  @Data
  public static class CandidateMemory {

    /** 是否启用画像记忆（评估后抽取 + Planner 注入） */
    private boolean enabled = true;

    /** Planner 大纲注入的最大记忆条数 */
    private int maxEntries = 8;
  }
}
