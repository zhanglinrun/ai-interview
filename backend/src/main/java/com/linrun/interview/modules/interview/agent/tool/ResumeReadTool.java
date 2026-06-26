package com.linrun.interview.modules.interview.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import com.linrun.interview.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 简历读取工具（LangChain4j @Tool 版）：面试官 Agent 用它了解候选人背景，出针对性的问题。
 *
 * <p>替代原 {@code AgentTool} 接口实现：方法用 {@link Tool} 注解，无参数，
 * 候选人简历 ID 通过 {@link AgentContextHolder} 从 ThreadLocal 取。模型自行决定
 * 是否需要查看简历（无简历时返回提示，引导其出通用题）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeReadTool {

    private static final int MAX_RESUME_CHARS = 2000;

    private final ResumeRepository resumeRepository;

    @Tool("读取候选人简历正文，了解其项目经历与技术栈，用于出针对性问题或决定追问点。"
        + "无需输入参数。当你想结合候选人背景出题时调用。")
    public String readResume() {
        AgentToolContext context = AgentContextHolder.get();
        if (context == null || context.resumeId() == null) {
            return "本次面试无候选人简历，请出该方向的标准面试题，不要暗示存在简历。";
        }

        try {
            Optional<ResumeEntity> resume = resumeRepository.findByUserIdAndId(
                UserContext.requireUserId(), context.resumeId());
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
