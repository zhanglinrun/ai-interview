package interview.guide.modules.interview.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.AiServices;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.interview.agent.model.AgentQuestionOutput;
import interview.guide.modules.interview.agent.model.AgentTraceStep;
import interview.guide.modules.interview.agent.model.InterviewAgentResult;
import interview.guide.modules.interview.agent.tool.AgentContextHolder;
import interview.guide.modules.interview.agent.tool.AgentToolContext;
import interview.guide.modules.interview.agent.tool.KnowledgeBaseSearchTool;
import interview.guide.modules.interview.agent.tool.ResumeReadTool;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试官 Agent 的 ReAct 决策循环（LangChain4j AiServices 版）。
 *
 * <p>替代原手写 think-act-observe 循环：工具调用由 LC4j 框架的 function-calling 自动驱动，
 * 模型自主决定调用 {@code @Tool}（知识库检索、简历读取）收集信息后出题。决策轨迹通过
 * {@link ToolExecutedEvent} 监听器在工具执行时捕获，转成 {@link AgentTraceStep} 供前端展示。
 *
 * <p>工具上下文（skillId/difficulty/resumeId/knowledgeBaseIds）通过 {@link AgentContextHolder}
 * ThreadLocal 传给 {@code @Tool} 方法，run 开始时 set、结束时 clear。
 *
 * <p>相比原手写循环的语义降级：AiServices 模式下轨迹只记录"调了哪些工具 + 参数 + 结果"，
 * 不再记录每轮的 thought 文本（thought 字段置空）；最终出题结果由
 * {@link AgentQuestionOutput} 结构化输出直接返回。
 */
@Slf4j
@Service
public class InterviewAgentLoop {

    private static final String METRIC_ROUNDS = "app.ai.interview_agent.rounds";
    private static final String METRIC_LATENCY = "app.ai.interview_agent.latency";
    private static final String METRIC_RUNS = "app.ai.interview_agent.runs";

    private final LlmProviderRegistry llmProviderRegistry;
    private final InterviewAgentProperties properties;
    private final KnowledgeBaseSearchTool knowledgeBaseSearchTool;
    private final ResumeReadTool resumeReadTool;
    private final MeterRegistry meterRegistry;

    public InterviewAgentLoop(LlmProviderRegistry llmProviderRegistry,
                              InterviewAgentProperties properties,
                              KnowledgeBaseSearchTool knowledgeBaseSearchTool,
                              ResumeReadTool resumeReadTool,
                              @Autowired(required = false) MeterRegistry meterRegistry) {
        this.llmProviderRegistry = llmProviderRegistry;
        this.properties = properties;
        this.knowledgeBaseSearchTool = knowledgeBaseSearchTool;
        this.resumeReadTool = resumeReadTool;
        this.meterRegistry = meterRegistry;
        log.info("[InterviewAgentLoop] 已注册工具: searchKnowledgeBase, readResume");
    }

    /**
     * 运行一轮面试官 Agent，产出下一道题及其决策轨迹。
     *
     * @param llmProvider     LLM 提供商（null 用默认）
     * @param context         面试上下文（技能/难度/简历/知识库）
     * @param conversationLog 已进行的问答历史（面试官视角的摘要，可为空）
     * @return 出题结果 + 工具调用轨迹
     */
    public InterviewAgentResult run(String llmProvider,
                                    AgentToolContext context,
                                    String conversationLog) {
        long startNanos = System.nanoTime();
        List<AgentTraceStep> trace = new ArrayList<>();
        int maxRounds = Math.max(1, properties.getMaxRounds());

        AgentContextHolder.set(context);
        try {
            ChatModel chatModel = llmProviderRegistry.getChatModelOrDefault(llmProvider);
            AiServiceListener<ToolExecutedEvent> toolListener = buildToolListener(trace);

            InterviewAgentAiService aiService = AiServices.builder(InterviewAgentAiService.class)
                .chatModel(chatModel)
                .tools(knowledgeBaseSearchTool, resumeReadTool)
                .registerListener(toolListener)
                .build();

            String skillId = context.skillId() == null ? "通用" : context.skillId();
            String difficulty = context.difficulty() == null ? "mid" : context.difficulty();
            String userMessage = buildUserMessage(conversationLog);

            AgentQuestionOutput output;
            try {
                output = aiService.nextQuestion(skillId, difficulty, userMessage);
            } catch (Exception e) {
                log.error("[InterviewAgentLoop] Agent 调用失败: {}", e.getMessage(), e);
                recordMetrics(trace.size(), startNanos, false);
                return fallbackResult(trace, trace.size());
            }

            InterviewAgentResult result = assembleResult(output, trace, maxRounds);
            recordMetrics(result.rounds(), startNanos, true);
            return result;
        } finally {
            AgentContextHolder.clear();
        }
    }

    /**
     * 工具执行监听器：每次 @Tool 执行后回调，把工具名/参数/结果转成轨迹步骤。
     */
    private AiServiceListener<ToolExecutedEvent> buildToolListener(List<AgentTraceStep> trace) {
        return new AiServiceListener<>() {
            @Override
            public java.lang.Class<ToolExecutedEvent> getEventClass() {
                return ToolExecutedEvent.class;
            }

            @Override
            public void onEvent(ToolExecutedEvent event) {
                ToolExecutionRequest request = event.request();
                String action = request != null ? request.name() : "";
                String actionInput = request != null ? request.arguments() : "";
                String observation = event.resultText() == null ? "" : event.resultText();
                int step = trace.size() + 1;
                trace.add(new AgentTraceStep(step, "", action, actionInput, observation));
                log.debug("[InterviewAgentLoop] 工具调用轨迹 #{}: action={}, observationLen={}",
                    step, action, observation.length());
            }
        };
    }

    private InterviewAgentResult assembleResult(AgentQuestionOutput output,
                                                List<AgentTraceStep> trace, int maxRounds) {
        if (output == null || output.question() == null || output.question().isBlank()) {
            return fallbackResult(trace, trace.size());
        }
        // 轮数 = 工具调用轮数 + 1（最后一轮出题）
        int rounds = Math.min(trace.size() + 1, maxRounds);
        // 追加 finish 轨迹步骤，保留原轨迹的"收尾"语义
        List<AgentTraceStep> fullTrace = new ArrayList<>(trace);
        fullTrace.add(new AgentTraceStep(rounds, "", "finish", "", "已产出面试题"));
        return new InterviewAgentResult(
            output.question().trim(),
            output.rationale() == null ? "" : output.rationale().trim(),
            output.isFollowUp(),
            List.copyOf(fullTrace),
            rounds);
    }

    private InterviewAgentResult fallbackResult(List<AgentTraceStep> trace, int round) {
        return new InterviewAgentResult(
            "请介绍一个你最有成就感的项目，并说明你在其中解决的关键技术难题。",
            "Agent 未能在限定轮数内产出结构化题目，回退到通用兜底题。",
            false,
            List.copyOf(trace),
            round);
    }

    private String buildUserMessage(String conversationLog) {
        StringBuilder sb = new StringBuilder();
        if (conversationLog != null && !conversationLog.isBlank()) {
            sb.append("已进行的面试问答摘要：\n").append(conversationLog).append("\n\n");
        } else {
            sb.append("面试尚未开始，这是第一道题。\n\n");
        }
        sb.append("请结合可用工具收集的信息（如已调用），决定下一道最该问的题，")
            .append("输出题目、出题理由，并标注是否追问（is_follow_up）。\n");
        return sb.toString();
    }

    private void recordMetrics(int rounds, long startNanos, boolean success) {
        if (!properties.isMetricsEnabled() || meterRegistry == null) {
            return;
        }
        meterRegistry.counter(METRIC_RUNS, Tags.of("status", success ? "success" : "failed")).increment();
        meterRegistry.summary(METRIC_ROUNDS).record(rounds);
        meterRegistry.timer(METRIC_LATENCY)
            .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
