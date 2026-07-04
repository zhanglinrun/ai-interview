package com.linrun.interview.modules.interview.agent;

import com.linrun.interview.modules.interview.agent.model.CriticVerdict;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Critic Agent：审核 Interviewer 产出的题目（契合度/具体性/不重复/追问合理性），
 * 不合格时给出 retryHint 触发 Reflexion 重生成。无工具、无记忆的单轮结构化输出。
 */
public interface CriticAiService {

  @SystemMessage(fromResource = "/prompts/agent/critic-system.st")
  CriticVerdict review(@UserMessage String reviewRequest);
}
