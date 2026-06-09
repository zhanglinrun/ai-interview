package interview.guide.modules.interview.agent;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.interview.agent.model.InterviewAgentRequest;
import interview.guide.modules.interview.agent.model.InterviewAgentResult;
import interview.guide.modules.interview.agent.tool.AgentToolContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面试官 Agent 控制器。
 * <p>
 * 暴露自适应出题接口：模型通过 ReAct 循环自主调用工具（知识库检索、简历读取），
 * 决定下一道最该问的题，并返回完整 think-act-observe 决策轨迹供前端展示。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "面试官 Agent", description = "ReAct 决策循环驱动的自适应出题")
public class InterviewAgentController {

    private final InterviewAgentLoop agentLoop;
    private final InterviewAgentProperties properties;

    /**
     * 让面试官 Agent 产出下一道题及其决策轨迹。
     */
    @PostMapping("/api/interview/agent/next-question")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewAgentResult> nextQuestion(@RequestBody InterviewAgentRequest request) {
        if (!properties.isEnabled()) {
            return Result.error("面试官 Agent 当前未启用");
        }
        log.info("面试官 Agent 出题: skillId={}, difficulty={}, resumeId={}, kbIds={}",
            request.skillId(), request.difficulty(), request.resumeId(), request.knowledgeBaseIds());

        AgentToolContext context = new AgentToolContext(
            request.skillId(),
            request.difficulty(),
            request.resumeId(),
            request.knowledgeBaseIds());

        InterviewAgentResult result = agentLoop.run(
            request.llmProvider(), context, request.conversationLog());
        return Result.success(result);
    }
}
