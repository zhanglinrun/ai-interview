package com.linrun.interview.business.service;

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

  /**
   * Reflexion 额外重试次数。总尝试 = maxReflexion + 1 对 Interviewer+Critic。
   * 默认 1，即最多 2 对。
   */
  private int maxReflexion = 1;

  /**
   * 会话内 ChatMemory 窗口大小（消息条数）。
   * 本场事实以已问主问题摘要落库为准，窗口只保留最近 1～2 轮原文（默认 4 条）。
   */
  private int memoryWindow = 4;

  /** 是否上报编排指标 */
  private boolean metricsEnabled = true;

  /** 跨场长期记忆 */
  private CandidateMemory candidateMemory = new CandidateMemory();

  /** 本机 agent-trace：是否把截断后的模型输入/输出写入 span */
  private Trace trace = new Trace();

  @Data
  public static class CandidateMemory {

    /** 是否启用长期记忆（评估后沉淀观测 + Planner 注入） */
    private boolean enabled = true;

    /** Planner 大纲注入的最大记忆条数 */
    private int maxEntries = 8;
  }

  @Data
  public static class Trace {

    /**
     * 是否把截断后的 messages / completion 写入 chat span。
     * 默认打开以便本机回放；简历工具结果仍会脱敏。
     */
    private boolean captureContent = true;

    /** 单段 input/output 最大字符数 */
    private int maxContentChars = 2000;
  }
}

