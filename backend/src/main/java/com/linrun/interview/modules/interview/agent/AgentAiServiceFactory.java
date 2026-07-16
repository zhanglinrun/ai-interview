package com.linrun.interview.modules.interview.agent;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.infrastructure.redis.RedisChatMemoryStore;
import com.linrun.interview.modules.interview.agent.model.AgentTraceStep;
import com.linrun.interview.modules.interview.agent.tool.AgentTraceCollector;
import com.linrun.interview.modules.interview.agent.tool.KnowledgeBaseSearchTool;
import com.linrun.interview.modules.interview.agent.tool.ResumeReadTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 四角色 Agent 的 AiServices 工厂：按 (ChatModel, agentType) 缓存实例。
 *
 * <p>反射解析 @Tool/schema 开销大，不每请求重建；key 用 ChatModel 实例本身——
 * BYOK 下 {@code getUserChatModel(userId)} 按用户返回各自实例（用户间互不串用），用户更新/删除
 * 「我的模型」触发 {@code evictUser} 后返回新实例，自然生成新缓存项。
 *
 * <p>Interviewer 挂载 ChatMemory（Redis 持久化窗口记忆，memoryId=面试 sessionId）
 * 与两个 @Tool；Planner/Critic 是无工具无记忆的单轮结构化输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAiServiceFactory {

  private final LlmProviderRegistry llmProviderRegistry;
  private final AgentOrchestrationProperties properties;
  private final KnowledgeBaseSearchTool knowledgeBaseSearchTool;
  private final ResumeReadTool resumeReadTool;
  private final RedisChatMemoryStore chatMemoryStore;

  private final ConcurrentHashMap<ChatModel, PlannerAiService> plannerCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ChatModel, InterviewerAiService> interviewerCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ChatModel, CriticAiService> criticCache = new ConcurrentHashMap<>();

  public PlannerAiService planner(Long userId) {
    ChatModel chatModel = llmProviderRegistry.getUserChatModel(userId);
    return plannerCache.computeIfAbsent(chatModel, model ->
        AiServices.builder(PlannerAiService.class)
            .chatModel(model)
            .build());
  }

  public InterviewerAiService interviewer(Long userId) {
    ChatModel chatModel = llmProviderRegistry.getUserChatModel(userId);
    return interviewerCache.computeIfAbsent(chatModel, model ->
        AiServices.builder(InterviewerAiService.class)
            .chatModel(model)
            .tools(knowledgeBaseSearchTool, resumeReadTool)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(Math.max(2, properties.getMemoryWindow()))
                .chatMemoryStore(chatMemoryStore)
                .build())
            .registerListener(toolTraceListener())
            .build());
  }

  public CriticAiService critic(Long userId) {
    ChatModel chatModel = llmProviderRegistry.getUserChatModel(userId);
    return criticCache.computeIfAbsent(chatModel, model ->
        AiServices.builder(CriticAiService.class)
            .chatModel(model)
            .build());
  }

  /**
   * 共享工具执行监听器：@Tool 执行后把工具名/参数/结果写进当前编排的 ThreadLocal 轨迹
   * （工具在调用线程同步执行，与 {@code AgentContextHolder} 同一假设）。
   */
  private AiServiceListener<ToolExecutedEvent> toolTraceListener() {
    return new AiServiceListener<>() {
      @Override
      public Class<ToolExecutedEvent> getEventClass() {
        return ToolExecutedEvent.class;
      }

      @Override
      public void onEvent(ToolExecutedEvent event) {
        ToolExecutionRequest request = event.request();
        AgentTraceCollector.append(
            AgentTraceStep.ROLE_INTERVIEWER,
            request != null ? request.name() : "",
            request != null ? request.arguments() : "",
            event.resultText() == null ? "" : event.resultText());
      }
    };
  }
}
