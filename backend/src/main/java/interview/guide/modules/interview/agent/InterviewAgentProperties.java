package interview.guide.modules.interview.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 面试官 Agent 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.interview-agent")
public class InterviewAgentProperties {

    /** 是否启用面试官 Agent（关闭时接口返回提示，不影响其他功能） */
    private boolean enabled = true;

    /** ReAct 循环最大轮数，防止模型无限调用工具 */
    private int maxRounds = 6;

    /** 单轮模型调用是否计入指标 */
    private boolean metricsEnabled = true;
}
