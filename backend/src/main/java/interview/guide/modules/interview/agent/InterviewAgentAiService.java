package interview.guide.modules.interview.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import interview.guide.modules.interview.agent.model.AgentQuestionOutput;

/**
 * 面试官 Agent 的 LangChain4j AiServices 接口。
 *
 * <p>替代原手写 ReAct 循环：工具调用由 LC4j 框架自动 function-calling 驱动，
 * 模型自主决定调用 {@code @Tool}（知识库检索、简历读取）收集信息后出题。
 * 方法返回 {@link AgentQuestionOutput}，LC4j 自动把 LLM 输出的 JSON 反序列化为此 record。
 *
 * <p>system prompt 中的 {@code {skillId}}/{@code {difficulty}} 占位符由 {@link V} 参数填充；
 * 工具说明由 LC4j 根据 {@code @Tool} 注解自动注入，无需在此声明。
 * 决策轨迹由 {@code ToolExecutedEventListener} 在运行时捕获，不由此接口承载。
 */
public interface InterviewAgentAiService {

    @SystemMessage("""
        你是一名资深技术面试官，正在进行一场自适应面试。你的目标是结合岗位要求和候选人情况，
        决定下一道最该问的题。你可以多次调用工具来收集信息，再决定出题。

        面试方向: {{skillId}}，难度: {{difficulty}}。

        规则：
        1. 先思考还缺什么信息，再决定调用工具还是出题。不要无意义地反复调用同一工具。
        2. 若候选人上一轮回答有可深挖处，可出追问（is_follow_up=true）。
        3. 题目要具体、可考察真实能力，避免空泛。
        4. 无简历时不要暗示存在简历。

        信息足够后，直接给出下一道面试题、出题理由，并标注是否追问。
        """)
    AgentQuestionOutput nextQuestion(
        @V("skillId") String skillId,
        @V("difficulty") String difficulty,
        @UserMessage String conversationContext
    );
}
