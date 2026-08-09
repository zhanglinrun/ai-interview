package com.linrun.interview.business.service;

import com.linrun.interview.ai.service.PromptSanitizer;
import com.linrun.interview.business.entity.ResumeEntity;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 简历读取工具实现：面试官 Agent 用它了解候选人背景，出针对性的问题。
 *
 * <p>替代原 {@code AgentTool} 接口实现：候选人简历 ID 从本次
 * {@link AgentExecutionContext} 取。模型自行决定
 * 是否需要查看简历（无简历时返回提示，引导其出通用题）。
 */
@Slf4j
@Component
public class ResumeReadTool {

    private static final int MAX_RESUME_CHARS = 2000;

    private final ResumeEntityMapper resumeEntityMapper;
    private final AgentExecutionContext executionContext;
    private PromptSanitizer promptSanitizer;

    @Autowired
    public ResumeReadTool(ResumeEntityMapper resumeEntityMapper) {
        this(resumeEntityMapper, null);
    }

    private ResumeReadTool(ResumeEntityMapper resumeEntityMapper,
                           AgentExecutionContext executionContext) {
        this.resumeEntityMapper = resumeEntityMapper;
        this.executionContext = executionContext;
    }

    @Autowired(required = false)
    public void setPromptSanitizer(PromptSanitizer promptSanitizer) {
        this.promptSanitizer = promptSanitizer;
    }

    public ResumeReadTool forContext(AgentExecutionContext context) {
        return new ResumeReadTool(resumeEntityMapper, context);
    }

    /**
     * Gateway adapter entry point.  The caller has already completed role and
     * ownership checks; this method still performs the ownership-constrained
     * lookup and returns a structured outcome so empty and failed reads are
     * distinguishable by the tool executor.
     */
    public ToolResult<String> readForGateway(Long userId, Long resumeId) {
        if (userId == null || resumeId == null) {
            return ToolResult.empty(null, "本次面试没有可读取的归属简历");
        }
        try {
            Optional<ResumeEntity> resume = EntityQueries.byUserAndId(
                resumeEntityMapper, userId, resumeId,
                ResumeEntity::getUserId, ResumeEntity::getId);
            if (resume.isEmpty() || resume.get().getResumeText() == null
                || resume.get().getResumeText().isBlank()) {
                return ToolResult.empty(null, "未找到候选人简历正文");
            }
            String text = resume.get().getResumeText().trim();
            if (promptSanitizer != null) {
                text = promptSanitizer.sanitize(text);
            }
            if (text.length() > MAX_RESUME_CHARS) {
                text = text.substring(0, MAX_RESUME_CHARS) + "...(简历较长，已截断)";
            }
            String bounded = "候选人简历正文：\n" + text;
            if (promptSanitizer != null) {
                bounded = promptSanitizer.wrapWithDelimiters("resume.read", bounded);
            }
            return ToolResult.success(bounded,
                "简历已读取并限制长度");
        } catch (Exception e) {
            log.warn("[ResumeReadTool] Gateway 读取简历失败: {}", e.getMessage());
            return ToolResult.degraded(null, "resume_read_failed", "简历读取失败");
        }
    }

    /**
     * Legacy helper retained for non-Agent callers. Model-visible access is
     * intentionally exposed only by {@link ResumeReadGatewayTool}, so every
     * Agent invocation passes through {@link ToolExecutor}.
     */
    public String readResume() {
        AgentToolContext context = executionContext == null ? null : executionContext.toolContext();
        if (context == null || context.resumeId() == null) {
            return "本次面试无候选人简历，请出该方向的标准面试题，不要暗示存在简历。";
        }

        try {
            Long userId = executionContext == null ? null : executionContext.userId();
            if (userId == null) {
                return "当前 Agent 上下文没有用户身份，请出通用题。";
            }
            Optional<ResumeEntity> resume = EntityQueries.byUserAndId(
                resumeEntityMapper, userId, context.resumeId(),
                ResumeEntity::getUserId, ResumeEntity::getId);
            if (resume.isEmpty() || resume.get().getResumeText() == null
                || resume.get().getResumeText().isBlank()) {
                return "未找到候选人简历正文，请基于该方向出通用题。";
            }
            String text = resume.get().getResumeText().trim();
            if (text.length() > MAX_RESUME_CHARS) {
                text = text.substring(0, MAX_RESUME_CHARS) + "...(简历较长，已截断)";
            }
            return "候选人简历正文：\n" + text;
        } catch (Exception e) {
            log.warn("[ResumeReadTool] 读取简历失败: {}", e.getMessage(), e);
            return "读取简历出错，请基于该方向出通用题。";
        }
    }
}
