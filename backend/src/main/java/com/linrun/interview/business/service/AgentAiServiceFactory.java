package com.linrun.interview.business.service;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.infra.redis.RedisChatMemoryStore;
import com.linrun.interview.business.vo.AgentTraceStep;
import com.linrun.interview.business.service.AgentExecutionContext;
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
 * 与简历读取 @Tool；岗位知识证据由决策器统一预检索，Planner/Critic 是无工具无记忆的单轮结构化输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAiServiceFactory {

  private final LlmProviderRegistry llmProviderRegistry;
  private final AgentOrchestrationProperties properties;
  private final ToolExecutor toolExecutor;
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
    return interviewer(userId, null);
  }

  /**
   * 创建绑定本次编排上下文的 Interviewer。
   *
   * <p>模型本体仍可按 ChatModel 缓存，但工具实例和监听器携带请求级 context，
   * 不再从全局 ThreadLocal 读取候选人信息。</p>
   */
  public InterviewerAiService interviewer(Long userId, AgentExecutionContext context) {
    ChatModel chatModel = llmProviderRegistry.getUserChatModel(userId);
    if (context != null) {
      return AiServices.builder(InterviewerAiService.class)
          .chatModel(chatModel)
          .tools(new ResumeReadGatewayTool(toolExecutor, context))
          .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
              .id(memoryId)
              .maxMessages(Math.max(2, properties.getMemoryWindow()))
              .chatMemoryStore(chatMemoryStore)
              .build())
          .registerListener(toolTraceListener(context))
          .build();
    }
    return interviewerCache.computeIfAbsent(chatModel, model ->
        AiServices.builder(InterviewerAiService.class)
            .chatModel(model)
            .tools(new ResumeReadGatewayTool(toolExecutor, null))
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(Math.max(2, properties.getMemoryWindow()))
                .chatMemoryStore(chatMemoryStore)
                .build())
            .registerListener(toolTraceListener(null))
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
   * 共享工具执行监听器：@Tool 执行后把工具名/参数/结果写入请求级上下文。
   */
  private AiServiceListener<ToolExecutedEvent> toolTraceListener(AgentExecutionContext context) {
    return new AiServiceListener<>() {
      @Override
      public Class<ToolExecutedEvent> getEventClass() {
        return ToolExecutedEvent.class;
      }

      @Override
      public void onEvent(ToolExecutedEvent event) {
        ToolExecutionRequest request = event.request();
        if (context != null) {
          context.append(
              AgentTraceStep.ROLE_INTERVIEWER,
              request != null ? request.name() : "",
              request == null || request.arguments() == null ? "" : "tool arguments redacted",
              summarizeToolObservation(request, event.resultText()));
        }
      }
    };
  }

  private String summarizeToolObservation(ToolExecutionRequest request, String resultText) {
    String name = request == null || request.name() == null ? "tool" : request.name();
    // resume.read may contain the entire resume in LangChain's result text;
    // never put that untrusted payload into the persisted agent span.
    if ("readResume".equals(name) || "resume.read".equals(name)) {
      return "resume.read executed; result redacted";
    }
    if (resultText == null || resultText.isBlank()) {
      return name + " completed";
    }
    String normalized = resultText.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "…";
  }
}
