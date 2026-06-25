package interview.guide.modules.interview.agent.tool;

/**
 * 面试官 Agent 工具上下文持有者（ThreadLocal）。
 *
 * <p>LangChain4j {@code @Tool} 方法签名不能直接接收 {@link AgentToolContext} 对象，
 * 因此在 {@code InterviewAgentLoop.run} 开始时把 context 塞进 ThreadLocal，
 * {@code @Tool} 方法体内通过 {@link #get()} 取出，run 结束时 {@link #clear()} 释放。
 *
 * <p>生命周期严格限定在单次 Agent 运行线程内，使用前必须 set、使用后必须 clear，
 * 避免线程池复用导致的上下文串号。
 */
public final class AgentContextHolder {

    private static final ThreadLocal<AgentToolContext> HOLDER = new ThreadLocal<>();

    private AgentContextHolder() {
    }

    public static void set(AgentToolContext context) {
        HOLDER.set(context);
    }

    public static AgentToolContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
