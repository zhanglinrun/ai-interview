package com.linrun.interview.business.service;

import dev.langchain4j.agent.tool.Tool;

/**
 * LangChain4j adapter for the server-owned {@code resume.read} tool.  The
 * model can see only this narrow adapter; authorization, ownership, timeout,
 * retry, audit and redaction stay inside {@link ToolExecutor}.
 */
public final class ResumeReadGatewayTool {
  private final ToolExecutor executor;
  private final AgentExecutionContext context;

  public ResumeReadGatewayTool(ToolExecutor executor, AgentExecutionContext context) {
    this.executor = executor;
    this.context = context;
  }

  @Tool("读取候选人简历正文，了解其项目经历与技术栈，用于出针对性问题或决定追问点。"
      + "无需输入参数。当你想结合候选人背景出题时调用。")
  public String readResume() {
    if (context == null || context.toolContext() == null
        || context.toolContext().resumeId() == null) {
      return "本次面试无候选人简历，请出该方向的标准面试题，不要暗示存在简历。";
    }
    ExecutionIdentity identity = context.identity();
    ToolExecutionContext executionContext = new ToolExecutionContext(
        context.userId(), context.sessionId(),
        identity == null ? null : identity.traceId(),
        identity == null ? null : identity.agentRunId(), null, "INTERVIEWER");
    ToolResult<String> result = executor.execute("resume.read", executionContext,
        java.util.Map.of("resumeId", context.toolContext().resumeId()), String.class);
    return switch (result.status()) {
      case SUCCESS -> result.data() == null ? "未找到候选人简历正文，请基于该方向出通用题。" : result.data();
      case EMPTY -> "未找到候选人简历正文，请基于该方向出通用题。";
      default -> "读取候选人简历暂时不可用，请基于该方向出通用题。";
    };
  }
}
