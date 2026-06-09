package interview.guide.modules.interview.agent.tool;

/**
 * 面试官 Agent 可调用的工具抽象。
 * <p>
 * 每个工具是一个 Spring Bean，由 ReAct 循环按模型决策动态选择调用。
 * 工具自身不感知循环，只负责"给定输入 -> 返回可观察的文本结果"。
 */
public interface AgentTool {

    /**
     * 工具名，模型在 action 字段中引用此名来调用。必须唯一、无空格。
     */
    String name();

    /**
     * 工具用途说明，会拼进 system prompt，帮助模型决定何时调用。
     */
    String description();

    /**
     * 执行工具。
     *
     * @param input   模型给出的 action_input（自然语言或简单参数）
     * @param context 本轮面试的只读上下文（技能方向、难度、简历ID等）
     * @return 可观察的文本结果（observation），将回填到 ReAct scratchpad
     */
    String execute(String input, AgentToolContext context);
}
