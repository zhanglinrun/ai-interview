package interview.guide.modules.interview.agent;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.interview.agent.model.AgentTraceStep;
import interview.guide.modules.interview.agent.model.InterviewAgentResult;
import interview.guide.modules.interview.agent.tool.AgentTool;
import interview.guide.modules.interview.agent.tool.AgentToolContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 面试官 Agent 的 ReAct 决策循环。
 * <p>
 * 这是项目里真正的 think-act-observe 循环，而非写死流程：
 * 每一轮让模型基于当前 scratchpad 自主决定调用哪个工具（或收尾出题），
 * 工具返回的 observation 回填进 scratchpad，进入下一轮，直到模型输出
 * action=finish 或达到最大轮数。每一轮都记录成可展示的轨迹。
 * <p>
 * 工具集复用项目已有能力（知识库混合检索、简历读取），由 Spring 注入。
 */
@Slf4j
@Service
public class InterviewAgentLoop {

    private static final String METRIC_ROUNDS = "app.ai.interview_agent.rounds";
    private static final String METRIC_LATENCY = "app.ai.interview_agent.latency";
    private static final String METRIC_RUNS = "app.ai.interview_agent.runs";

    private final LlmProviderRegistry llmProviderRegistry;
    private final InterviewAgentProperties properties;
    private final Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry;

    public InterviewAgentLoop(LlmProviderRegistry llmProviderRegistry,
                              InterviewAgentProperties properties,
                              List<AgentTool> tools,
                              @Autowired(required = false) MeterRegistry meterRegistry) {
        this.llmProviderRegistry = llmProviderRegistry;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        for (AgentTool tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
        log.info("[InterviewAgentLoop] 已注册 {} 个工具: {}", toolsByName.size(), toolsByName.keySet());
    }

    /**
     * 运行一轮面试官 Agent，产出下一道题及其决策轨迹。
     *
     * @param llmProvider     LLM 提供商（null 用默认）
     * @param context         面试上下文（技能/难度/简历/知识库）
     * @param conversationLog 已进行的问答历史（面试官视角的摘要，可为空）
     * @return 出题结果 + 完整 ReAct 轨迹
     */
    public InterviewAgentResult run(String llmProvider,
                                    AgentToolContext context,
                                    String conversationLog) {
        long startNanos = System.nanoTime();
        List<AgentTraceStep> trace = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        int maxRounds = Math.max(1, properties.getMaxRounds());

        ChatClient chatClient = llmProviderRegistry.getPlainChatClient(llmProvider);
        String systemPrompt = buildSystemPrompt(context);

        InterviewAgentResult result = null;
        int round = 0;
        for (round = 1; round <= maxRounds; round++) {
            boolean lastRound = round == maxRounds;
            String userPrompt = buildUserPrompt(conversationLog, scratchpad.toString(), lastRound);

            String raw;
            try {
                raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            } catch (Exception e) {
                log.error("[InterviewAgentLoop] 第 {} 轮模型调用失败: {}", round, e.getMessage(), e);
                break;
            }

            JsonNode node = parseJson(raw);
            if (node == null) {
                log.warn("[InterviewAgentLoop] 第 {} 轮输出非 JSON，记录后继续", round);
                trace.add(new AgentTraceStep(round, "（模型输出无法解析为决策 JSON）",
                    "parse_error", "", truncateForTrace(raw)));
                continue;
            }

            String thought = node.path("thought").asString("");
            String action = node.path("action").asString("");

            if ("finish".equalsIgnoreCase(action) || lastRound) {
                result = buildFinishResult(node, thought, trace, round);
                trace.add(new AgentTraceStep(round, thought, "finish",
                    "", "已产出面试题"));
                break;
            }

            String actionInput = node.path("action_input").asString("");
            AgentTool tool = toolsByName.get(action);
            String observation;
            if (tool == null) {
                observation = "未知工具：" + action + "。可用工具：" + toolsByName.keySet();
            } else {
                observation = tool.execute(actionInput, context);
            }

            trace.add(new AgentTraceStep(round, thought, action, actionInput, observation));
            scratchpad.append("\n[第").append(round).append("轮]")
                .append("\nthought: ").append(thought)
                .append("\naction: ").append(action)
                .append("\naction_input: ").append(actionInput)
                .append("\nobservation: ").append(observation)
                .append("\n");
        }

        if (result == null) {
            result = fallbackResult(trace, Math.min(round, maxRounds));
        }

        recordMetrics(result.rounds(), startNanos);
        return result;
    }

    private InterviewAgentResult buildFinishResult(JsonNode node, String thought,
                                                   List<AgentTraceStep> trace, int round) {
        String question = node.path("question").asString("");
        String rationale = node.path("rationale").asString(thought);
        boolean isFollowUp = node.path("is_follow_up").asBoolean(false);
        if (question.isBlank()) {
            return fallbackResult(trace, round);
        }
        return new InterviewAgentResult(question.trim(), rationale.trim(), isFollowUp,
            List.copyOf(trace), round);
    }

    private InterviewAgentResult fallbackResult(List<AgentTraceStep> trace, int round) {
        return new InterviewAgentResult(
            "请介绍一个你最有成就感的项目，并说明你在其中解决的关键技术难题。",
            "Agent 未能在限定轮数内产出结构化题目，回退到通用兜底题。",
            false,
            List.copyOf(trace),
            round);
    }

    private String buildSystemPrompt(AgentToolContext context) {
        StringBuilder tools = new StringBuilder();
        for (AgentTool tool : toolsByName.values()) {
            tools.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        return """
            你是一名资深技术面试官，正在进行一场自适应面试。你的目标是结合岗位要求和候选人情况，
            决定下一道最该问的题目。你可以多轮调用工具来收集信息，再决定出题。

            可用工具：
            %s
            面试方向: %s，难度: %s。

            请严格按 ReAct 协议工作，每次只输出一个 JSON 对象，不要输出任何额外文字或 Markdown：
            - 需要调用工具时：{"thought":"你的思考","action":"工具名","action_input":"工具输入"}
            - 信息足够、决定出题时：{"thought":"你的思考","action":"finish","question":"下一道面试题","rationale":"出题理由","is_follow_up":false}

            规则：
            1. 先思考还缺什么信息，再决定调用工具还是出题。不要无意义地反复调用同一工具。
            2. 若候选人上一轮回答有可深挖处，可出追问（is_follow_up=true）。
            3. 题目要具体、可考察真实能力，避免空泛。
            4. 无简历时不要暗示存在简历。
            """.formatted(tools.toString(),
            context.skillId() == null ? "通用" : context.skillId(),
            context.difficulty() == null ? "mid" : context.difficulty());
    }

    private String buildUserPrompt(String conversationLog, String scratchpad, boolean lastRound) {
        StringBuilder sb = new StringBuilder();
        if (conversationLog != null && !conversationLog.isBlank()) {
            sb.append("已进行的面试问答摘要：\n").append(conversationLog).append("\n\n");
        } else {
            sb.append("面试尚未开始，这是第一道题。\n\n");
        }
        if (!scratchpad.isBlank()) {
            sb.append("你已收集的信息（ReAct 历史）：\n").append(scratchpad).append("\n");
        }
        if (lastRound) {
            sb.append("\n注意：这是最后一轮，必须直接输出 action=finish 的出题 JSON。\n");
        } else {
            sb.append("\n请输出你这一轮的决策 JSON。\n");
        }
        return sb.toString();
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        // 去掉可能的 Markdown 代码块包裹
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }
        // 截取第一个 { 到最后一个 } 之间的内容，容忍前后噪声
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private String truncateForTrace(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private void recordMetrics(int rounds, long startNanos) {
        if (!properties.isMetricsEnabled() || meterRegistry == null) {
            return;
        }
        meterRegistry.counter(METRIC_RUNS, Tags.of("status", "success")).increment();
        meterRegistry.summary(METRIC_ROUNDS).record(rounds);
        meterRegistry.timer(METRIC_LATENCY)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
