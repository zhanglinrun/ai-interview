package com.linrun.interview.modules.interview.agent;

import com.linrun.interview.modules.interview.agent.model.AgentQuestionOutput;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Interviewer Agent：按大纲节点自适应出题（含追问）；岗位知识由决策器预检索，
 * Interviewer 仅按需调用简历读取工具。
 *
 * <p>会话内上下文由 LangChain4j ChatMemory 承载（{@code @MemoryId} = 面试 sessionId，
 * Redis 持久化窗口记忆），替代旧版手拼 conversationLog；Critic 打回时编排器把
 * retryHint 写进新一轮 instruction（Reflexion）。
 */
public interface InterviewerAiService {

  @SystemMessage(fromResource = "/prompts/agent/interviewer-system.st")
  AgentQuestionOutput nextQuestion(
      @MemoryId String sessionId,
      @V("skillId") String skillId,
      @V("difficulty") String difficulty,
      @UserMessage String instruction
  );
}
