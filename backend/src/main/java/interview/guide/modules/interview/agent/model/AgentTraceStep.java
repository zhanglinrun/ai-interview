package interview.guide.modules.interview.agent.model;

/**
 * ReAct 单步轨迹：一轮 think-act-observe 的可视化记录。
 * <p>
 * 这是面试官 Agent "可解释、可追溯" 的核心载体，前端按此展示
 * 模型每一步思考了什么、调用了哪个工具、观察到什么结果。
 *
 * @param step        第几轮（从 1 开始）
 * @param thought     模型的思考（think）
 * @param action      选择调用的工具名（act），收尾轮为 "finish"
 * @param actionInput 工具输入
 * @param observation 工具返回的观察结果（observe）；收尾轮为空
 */
public record AgentTraceStep(
    int step,
    String thought,
    String action,
    String actionInput,
    String observation
) {}
